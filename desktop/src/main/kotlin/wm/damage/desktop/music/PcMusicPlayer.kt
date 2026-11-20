package wm.damage.desktop.music

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import wm.damage.core.util.Log
import wm.damage.core.windows.music.AudioProfile
import wm.damage.core.windows.music.MusicLibrary
import wm.damage.core.windows.music.Output
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.PlayerCore
import wm.damage.core.windows.music.TrackRef

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-4, package K (the lean spec's §3.4, §3.6; answers 1, 3 and 5): the PC's player — `PlayerCore`'s transport
 * rules over lane P's `MpvSink`, on one thread of its own. It plays only while `out` names the PC (the core's guard),
 * the original file (lossless, stereo), at mpv's own volume; no prefetch, one output (the device he names, PipeWire's
 * default until then), no boost. One per process, made by the PC's host builders; the player program starts at the
 * first open that needs sound, never here.
 */
class PcMusicPlayer(
    library: MusicLibrary,
    /** The player program (lane P's [MpvProcess]; a fake's socket in the tests): started lazily by the sink, stopped
     *  by [close]. */
    private val process: MpvLink,
    device: String?,
    /** What mpv opens for a track: its local file (`LocalMusicLibrary.resolveMedia`, lossless). */
    private val media: (TrackRef) -> String,
    /** The PC's one notice of its own (decisions B.4): "the PC output played until 15:10". */
    private val notice: (String) -> Unit = {},
    clock: () -> Long = { System.currentTimeMillis() },
) : PlayerCore(library, clock) {
    private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "music-pc").apply { isDaemon = true; me = this } }
    @Volatile private var me: Thread? = null
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "music-pc-io").apply { isDaemon = true } }

    private val events = object : MpvSink.Events {
        override fun ended() = onEnded()
        override fun failed(detail: String) = onSinkError(detail)
        override fun restarted(playing: Boolean) = onStarted(playing)
        override fun exited(detail: String) = sinkExited(detail)
    }

    override val sink: Sink = MpvSink(process, events, device)
    override val startsAsync: Boolean = true

    override fun post(block: () -> Unit) {
        if (Thread.currentThread() === me) { run(block); return }
        thread.execute { run(block) }
    }

    private fun run(block: () -> Unit) = try {
        block()
    } catch (e: Exception) {
        Log.e("player-pc", "a player task failed", e)
    }

    override fun <T> runAsync(block: () -> T, then: (Result<T>) -> Unit) {
        io.execute {
            val r = try { Result.success(block()) } catch (e: Exception) { Result.failure(e) }
            post { then(r) }
        }
    }

    override fun mediaFor(t: TrackRef): String = media(t)

    /** The phone (a move back), then the PC's one device. */
    override fun hostOutputs(): List<Output> = listOf(Output.PHONE_OUTPUT) + sink.outputs()

    override fun movedAway(text: String) {
        super.movedAway(text)
        try { notice(text) } catch (e: Exception) { Log.e("player-pc", "the output notice was not raised", e) }
    }

    init {
        curProfile = LOSSLESS
        curOutput = Output.PC
        // the sleep deadline and the 10 s state refresh run on ticks while playing (pacing, not a timer wrapper)
        thread.scheduleAtFixedRate({ run { if (play == PlayState.PLAYING) tick() } }, 1, 1, TimeUnit.SECONDS)
        changed()
    }

    /** The process's orderly end (the shutdown hook): `now` written stopped at the exact place, then the player
     *  program stopped (lane P: its stdin closed, then SIGTERM, then SIGKILL). */
    fun close(why: String) {
        val done = java.util.concurrent.CountDownLatch(1)
        endOrderly(why)
        post { done.countDown() }
        done.await()
        try { (process as? MpvProcess)?.stop(why) } catch (e: Exception) { Log.e("player-pc", "the player program did not stop", e) }
        thread.shutdown()
        io.shutdown()
    }

    companion object {
        /** Answer 5: the original file, stereo, no loudness normalisation. */
        val LOSSLESS = AudioProfile(AudioProfile.Quality.LOSSLESS, 2, false)
    }
}
