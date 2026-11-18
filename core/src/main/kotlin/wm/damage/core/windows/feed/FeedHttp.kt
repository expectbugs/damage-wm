package wm.damage.core.windows.feed

import java.io.IOException
import java.net.HttpURLConnection
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
interface FeedHttp {
    fun get(url: String): HttpReplyB = get(url, emptyMap())
    fun get(url: String, headers: Map<String, String>): HttpReplyB
    /** A form POST (Slashdot's `ajax.pl`). A seam that cannot post says so. */
    fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpReplyB =
        throw UnsupportedOperationException("this http seam does not post")

    /** B4, the fetch supervisor: ends every fetch in flight since before [nowMs] − [limitMs] (its connection closed,
     *  its caller answered with an [IOException] that says so) and names them. A seam with no sockets ends none. */
    fun sweep(nowMs: Long, limitMs: Long): List<String> = emptyList()
}

/** A host asked us to wait (429/503 with `Retry-After`, or our own pace):
 *  [retryAtMs] is when the next request may go. Never retried inside the
 *  pacing — the engine schedules and the window says the time. */
class RateLimited(val host: String, val retryAtMs: Long) :
    IOException("$host rate-limited · retry at $retryAtMs")

/**
 * The real fetcher: `HttpURLConnection`, one user agent, redirects followed by hand (the JDK will not cross
 * http → https on its own, and every one of these sites redirects somewhere). NO TIMEOUTS — the standing rule;
 * liveness is the engine's pacing. Build S1-3 B4: each exchange registers its connection and its start, so the
 * engine's loop can end one that has been in flight for minutes ([sweep]) — a site that stops answering mid-body
 * otherwise held its source (and an open article's "loading") until a restart.
 */
class RealFeedHttp(private val userAgent: String, private val clock: () -> Long = { System.currentTimeMillis() }) : FeedHttp {
    private class Flight(val url: String, val startMs: Long, val conn: HttpURLConnection) {
        @Volatile var ended = false
    }
    private val flights = java.util.concurrent.ConcurrentHashMap.newKeySet<Flight>()

    override fun sweep(nowMs: Long, limitMs: Long): List<String> {
        val out = ArrayList<String>()
        for (f in flights) if (!f.ended && nowMs - f.startMs >= limitMs) {
            f.ended = true
            f.conn.disconnect()          // the blocked read ends with an IOException on its own thread
            out += f.url
        }
        return out
    }

    private fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null, type: String? = null): HttpReplyB {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        val f = Flight(url, clock(), conn)
        flights += f
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            for ((k, v) in mapOf("User-Agent" to userAgent, "Accept" to "*/*") + headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (type != null) conn.setRequestProperty("Content-Type", type)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val bytes = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            // an ended exchange's read may return what had come as if it were the whole body: never taken as an answer
            if (f.ended) throw IOException("ended")
            val hdrs = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) hdrs[k] = v
            return HttpReplyB(status, hdrs, bytes)     // a whole body read: the socket goes back to the keep-alive pool
        } catch (e: IOException) {
            conn.disconnect()
            if (f.ended) throw IOException("no answer from ${URI(url).host} for ${STUCK_MS / 60_000} min — ended; tried again at the next due time", e)
            throw IOException("$method $url: ${e.message}", e)    // a body that ends early is a failure, never an empty answer
        } finally {
            flights -= f
        }
    }

    override fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpReplyB =
        exchange("POST", url, headers, Http.formEncode(form).toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded")

    override fun get(url: String, headers: Map<String, String>): HttpReplyB {
        var u = url
        repeat(6) {
            val r = exchange("GET", u, headers)
            if (r.status in 301..308) {
                val loc = r.header("Location") ?: throw IOException("HTTP ${r.status} from $u without a Location")
                u = URI(u).resolve(loc.trim()).toString()
                return@repeat
            }
            return r
        }
        throw IOException("too many redirects from $url")
    }

    companion object {
        /** B4: a fetch in flight this long is ended by the engine's loop (an outside supervisor, not a timeout). */
        const val STUCK_MS = 5 * 60_000L
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

    override fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpReplyB {
        pace(hostKey(url))
        return checkLimit(hostKey(url), inner.post(url, form, headers))
    }

    override fun get(url: String, headers: Map<String, String>): HttpReplyB {
        val host = hostKey(url)
        pace(host)
        return checkLimit(host, inner.get(url, headers))
    }

    override fun sweep(nowMs: Long, limitMs: Long): List<String> = inner.sweep(nowMs, limitMs)

    private fun pace(host: String) {
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
    }

    private fun checkLimit(host: String, r: HttpReplyB): HttpReplyB {
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
