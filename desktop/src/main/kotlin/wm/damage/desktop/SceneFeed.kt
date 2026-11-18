package wm.damage.desktop

import java.util.concurrent.atomic.AtomicInteger
import wm.damage.core.windows.feed.FeedProvider
import wm.damage.core.windows.feed.LineArt
import wm.damage.core.windows.feed.SourceKind

/**
 * The snapshot judge's Feed provider (build S1-3 stage J): [real] (the scripted feed) passed through unchanged, with a
 * count of the calls in flight. The window makes those calls off its loop and applies each answer on the loop
 * afterwards, so a scene that waits only for the shell to be at rest can be saved in the instant between a request
 * (an episode's strip, an article's image, a list page) and its answer — with "loading page 3" in the status bar in one
 * render and "idle" in the next. A scene waits for [inFlight] to read 0 as well. Harness only: nothing here is a
 * product path.
 */
class SceneFeed(private val real: FeedProvider) : FeedProvider by real {
    private val busy = AtomicInteger(0)

    /** Provider calls started and not yet answered. */
    fun inFlight(): Int = busy.get()

    private inline fun <T> track(call: () -> T): T {
        busy.incrementAndGet()
        try { return call() } finally { busy.decrementAndGet() }
    }

    override fun sources() = track { real.sources() }
    override fun items(sourceId: String, offset: Int, limit: Int) = track { real.items(sourceId, offset, limit) }
    override fun item(itemId: String) = track { real.item(itemId) }
    override fun article(itemId: String) = track { real.article(itemId) }
    override fun comic(itemId: String, width: Int, levels: Int, mode: LineArt) = track { real.comic(itemId, width, levels, mode) }
    override fun bingeIndex(sourceId: String) = track { real.bingeIndex(sourceId) }
    override fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt) =
        track { real.bingeEpisode(sourceId, num, width, levels, mode) }
    override fun comments(itemId: String) = track { real.comments(itemId) }
    override fun comicAt(sourceId: String, num: Int) = track { real.comicAt(sourceId, num) }
    override fun browse(kind: SourceKind, name: String) = track { real.browse(kind, name) }
    override fun image(url: String, width: Int, levels: Int, mode: LineArt) = track { real.image(url, width, levels, mode) }
    override fun articleByUrl(url: String, title: String) = track { real.articleByUrl(url, title) }
}
