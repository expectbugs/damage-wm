package wm.damage.core.windows.music

import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.JsonObject

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-4, package K (the lean spec's §3.2, §3.5): the one door on the PC for every act on Music's playback — the
 * phone's `request("player", …)` now, the page's program ops in half B. A transport or queue verb goes to the host that
 * plays: the PC's own player in this process, or the phone's through the relay. A move goes to its target. Each act is
 * one `program` journal note, with who asked. Playlists, Ask, YouTube and lyrics stay the library's requests.
 */
class MusicProgram(
    /** The PC's player (on the record as host `pc`). */
    private val pc: PlayerCore,
    /** The relay to the phone's player (`MusicService.relay`). */
    private val relay: (verb: String, args: JsonObject, done: (String?, Boolean) -> Unit) -> Unit,
    /** True while the phone's `app` connection is up (`AppNet.connected`). */
    private val phoneUp: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** One `program` journal note per act. */
    private val note: (String) -> Unit = {},
) {
    /** Applies [verb] asked by [who] (`phone`, `local`, a page's device); [done] gets null or the refusal in words. */
    fun act(verb: String, args: JsonObject, who: String, done: (String?) -> Unit) {
        val answer: (String?) -> Unit = { why ->
            note("$who music $verb " + (why?.let { "refused: $it" } ?: "taken"))
            done(why)
        }
        if (verb !in MusicCore.CONTROL_VERBS) { answer("no such verb \"$verb\""); return }
        if (verb == "move") {
            val to = MusicCore.text(args["to"])
            val t = MusicCore.long(args["t"]) ?: clock()
            when {
                to != MusicCore.PC && to != MusicCore.PHONE -> answer("a move names the phone or the PC")
                to == MusicCore.PHONE && who == MusicCore.PHONE -> answer("the phone moves the output to itself")
                // §3.5: a browser's move to the PC while the phone does not answer asks first
                to == MusicCore.PC && who != MusicCore.PHONE && who != wm.damage.core.app.Peer.LOCAL.device &&
                    !pc.isOutput && !phoneUp() && MusicCore.bool(args["force"]) != true ->
                    answer("the phone has not answered and may still be playing; move anyway?")
                else -> pc.move(to, args.takeIf { who == MusicCore.PHONE && it["e"] != null }, t, answer)
            }
            return
        }
        when {
            pc.isOutput -> pc.command(verb, args, answer)
            who == MusicCore.PHONE -> answer("the phone is the output")          // never relayed back to the asker
            else -> relay(verb, args) { why, _ -> answer(why) }
        }
    }

    /** [act], waiting for its answer (the music channel's request, served on its own thread: §3.3). */
    fun actAndWait(verb: String, args: JsonObject, who: String): String? {
        val f = CompletableFuture<String?>()
        act(verb, args, who) { f.complete(it) }
        return f.get()
    }
}
