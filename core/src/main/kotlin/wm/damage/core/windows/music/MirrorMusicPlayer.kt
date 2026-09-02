package wm.damage.core.windows.music

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wm.damage.core.util.Log

/**
 * The desktop's player (`MUSIC.md` §3.1): NO sink — it shows the phone's
 * synced record (`window.music.player`, last-write-wins) and refuses every
 * transport command LOUDLY ("playback needs the phone"), exactly as G2CC
 * did in the PC-only configuration. The record it hands back is the one it
 * received, byte-equal, so the store never re-stamps it (no LWW ping-pong).
 */
class MirrorMusicPlayer(private val clock: () -> Long = { System.currentTimeMillis() }) : MusicPlayer {
    private val listeners = CopyOnWriteArrayList<MusicPlayer.Listener>()
    private val engine = QueueEngine()
    @Volatile private var record: JsonObject = JsonObject(emptyMap())
    @Volatile private var st = PlayerState(pcLink = PcLink(true))
    @Volatile private var recPos = 0L
    @Volatile private var recAt = 0L

    override val state: PlayerState get() = st

    private fun refuse(what: String) {
        Log.i("music-mirror", "$what refused — playback needs the phone")
        for (l in listeners) try { l.event(PlayerEvent.Error("playback needs the phone")) } catch (e: Exception) { Log.e("music-mirror", "listener", e) }
    }

    override fun play() = refuse("play")
    override fun pause() = refuse("pause")
    override fun toggle() = refuse("toggle")
    override fun next() = refuse("next")
    override fun prev() = refuse("prev")
    override fun stop() = refuse("stop")
    override fun seekTo(ms: Long) = refuse("seek")
    override fun seekBy(ms: Long) = refuse("seek")
    override fun playQueue(tracks: List<TrackRef>, startIndex: Int, mode: Mode, label: String) = refuse("play")
    override fun playFrom(qid: Long) = refuse("play")
    override fun playNext(tracks: List<TrackRef>) = refuse("queue edit")
    override fun append(tracks: List<TrackRef>) = refuse("queue edit")
    override fun replace(tracks: List<TrackRef>, label: String) = refuse("play")
    override fun remove(qid: Long) = refuse("queue edit")
    override fun move(qid: Long, delta: Int) = refuse("queue edit")
    override fun clear() = refuse("clear")
    override fun shuffleRest() = refuse("shuffle")
    override fun setMode(mode: Mode) = refuse("mode")
    override fun setVolume(pct: Int, cause: String) = refuse("volume")
    override fun setBoost(pct: Int) = refuse("boost")
    override fun setOutput(id: String) = refuse("output")
    override fun outputs(): List<Output> = st.outputs
    override fun setBackend(b: Backend) = refuse("backend")
    override fun backToPc() = refuse("backend")
    override fun setSleep(s: Sleep) = refuse("sleep")
    override fun setHoldVolume(on: Boolean) = refuse("hold my volume")
    override fun setProfile(p: AudioProfile) = refuse("profile")
    override fun setPrefetch(n: Int) = refuse("prefetch")
    override fun setSpotifyFallback(auto: Boolean) = refuse("Spotify fallback")
    override fun setFocused(focused: Boolean) {}

    /** The phone's position, extrapolated while its record said PLAYING. */
    override fun positionMs(): Long = if (st.play == PlayState.PLAYING) recPos + (clock() - recAt) else recPos

    override fun addListener(l: MusicPlayer.Listener) { if (listeners.addIfAbsent(l)) try { l.state(st) } catch (e: Exception) { Log.e("music-mirror", "listener", e) } }
    override fun removeListener(l: MusicPlayer.Listener) { listeners.remove(l) }

    override fun persist(): JsonObject = record

    override fun restore(o: JsonObject) {
        record = o
        try {
            (o["engine"] as? JsonObject)?.let { engine.fromJson(it) }
            val play = when (o["play"]?.jsonPrimitive?.contentOrNull) { "PLAYING" -> PlayState.PLAYING; "PAUSED" -> PlayState.PAUSED; else -> PlayState.STOPPED }
            recPos = o["posMs"]?.jsonPrimitive?.longOrNull ?: 0L
            recAt = o["stamp"]?.jsonPrimitive?.longOrNull ?: clock()
            st = PlayerState(
                backend = o["backend"]?.jsonPrimitive?.contentOrNull?.let { n -> Backend.entries.firstOrNull { it.name == n } } ?: Backend.LIBRARY,
                play = play, queue = engine.entries.toList(), index = engine.index, posMs = recPos,
                durMs = engine.current?.track?.durMs?.toLong() ?: 0L, mode = engine.mode,
                volume = o["volume"]?.jsonPrimitive?.intOrNull ?: 50, holdVolume = o["holdVolume"]?.jsonPrimitive?.booleanOrNull ?: true,
                profile = o["profile"]?.jsonPrimitive?.contentOrNull?.let { AudioProfile.parse(it) } ?: AudioProfile.DEFAULT,
                label = engine.label, spotifyAuto = o["spotifyAuto"]?.jsonPrimitive?.booleanOrNull ?: false,
                output = o["output"]?.jsonPrimitive?.contentOrNull ?: Output.AUTO,
                problem = "playback needs the phone",
            )
            for (l in listeners) try { l.state(st) } catch (e: Exception) { Log.e("music-mirror", "listener", e) }
        } catch (e: Exception) {
            Log.w("music-mirror", "player record unreadable: ${e.message}")
        }
    }
}
