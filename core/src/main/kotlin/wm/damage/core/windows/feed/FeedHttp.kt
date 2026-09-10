package wm.damage.core.windows.feed

import java.io.IOException
import java.net.URI
import wm.damage.core.util.Http
import wm.damage.core.util.Log

/** What a fetch hands back: the status, the headers, the bytes. */
class HttpReplyB(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun text(): String = body.toString(Charsets.UTF_8)
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * FEED.md §3.6 — the one seam every fetch goes through (`GET url` → status +
 * headers + bytes), so tests replay today's captured responses without a
 * socket and the phone and the PC share every fetcher.
 */
fun interface FeedHttp {
    fun get(url: String): HttpReplyB
}

/** A host asked us to wait (429/503 with `Retry-After`, or our own pace):
 *  [retryAtMs] is when the next request may go. Never retried inside the
 *  pacing — the engine schedules and the window says the time. */
class RateLimited(val host: String, val retryAtMs: Long) :
    IOException("$host rate-limited · retry at $retryAtMs")

/**
 * The real fetcher: `HttpURLConnection` through [Http.request], one user
 * agent, redirects followed by hand (the JDK will not cross http → https on
 * its own, and every one of these sites redirects somewhere). NO TIMEOUTS —
 * the standing rule; liveness is the engine's pacing.
 */
class RealFeedHttp(private val userAgent: String) : FeedHttp {
    override fun get(url: String): HttpReplyB {
        var u = url
        repeat(6) {
            val r = Http.request("GET", u, mapOf("User-Agent" to userAgent, "Accept" to "*/*"))
            if (r.status in 301..308) {
                val loc = r.header("Location") ?: throw IOException("HTTP ${r.status} from $u without a Location")
                u = URI(u).resolve(loc.trim()).toString()
                return@repeat
            }
            return HttpReplyB(r.status, r.headers, r.body)
        }
        throw IOException("too many redirects from $url")
    }
}

/**
 * Per-host pacing in front of any [FeedHttp] (FEED.md §2.1): Reddit answers
 * a burst with 429s, so one request per [paceMs] per host, `Retry-After`
 * honoured, a short wait taken here, a long one thrown as [RateLimited] so
 * the caller can say it instead of sitting on a socket for a minute. The
 * host key is the registered domain (`reddit.com` covers `www.` and
 * `old.`; `xkcd.com` covers `imgs.`).
 */
class PacedHttp(
    private val inner: FeedHttp,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /** The minimum gap per host. */
    private val paceMs: (host: String) -> Long = { h -> if (h == "reddit.com") 60_000L else 1_000L },
    /** Waits up to this long are taken inline; longer ones are thrown. */
    private val maxInlineWaitMs: Long = 5_000L,
    /** A 429/503 without `Retry-After` backs off this long. */
    private val defaultBackoffMs: Long = 120_000L,
) : FeedHttp {
    private val nextAllowed = HashMap<String, Long>()
    private val lock = Any()

    /** When [host] may next be asked, or 0 — for the state line. */
    fun retryAt(host: String): Long = synchronized(lock) { nextAllowed[host] ?: 0L }

    override fun get(url: String): HttpReplyB {
        val host = hostKey(url)
        synchronized(lock) {
            val at = nextAllowed[host] ?: 0L
            val now = clock()
            if (now < at) {
                val wait = at - now
                if (wait > maxInlineWaitMs) throw RateLimited(host, at)
                pause(wait)
            }
            // reserve the slot NOW: two callers in flight for one host pace too
            nextAllowed[host] = clock() + paceMs(host)
        }
        val r = inner.get(url)
        if (r.status == 429 || r.status == 503) {
            val ra = r.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000 } ?: defaultBackoffMs
            val at = clock() + ra
            synchronized(lock) { nextAllowed[host] = at }
            Log.w("feed-http", "$host answered HTTP ${r.status} — waiting ${ra / 1000} s")
            throw RateLimited(host, at)
        }
        return r
    }

    companion object {
        fun hostKey(url: String): String {
            val h = try { URI(url).host } catch (e: Exception) { null } ?: throw IOException("no host in '$url'")
            val parts = h.lowercase().split('.')
            return if (parts.size <= 2) h.lowercase() else parts.takeLast(2).joinToString(".")
        }
    }
}
