package wm.damage.core.app

import kotlinx.serialization.json.JsonElement

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.4 and §5 V3; `CONTRACT.md` §2.4 "never queued", §4.4; decisions A.7): an op
 * into an outside program — the torrent client, the tracker, the filesystem, `xdg-open` — from an interface on the
 * `app` lane. It takes no place in a stream: it carries `(device, conn, k)`, the PC refuses it once its connection has
 * ended, and it is never resent. The `app` lane checks it at apply time (the connection, then the level the action
 * needs), then hands it to the window's handler on the window's own pool, never on the core executor; the answer goes
 * back through the executor to that connection only. It changes no record item, so the record's log is not used: each
 * op is one `program` journal note.
 */

/** A window's program ops on the PC (`AppNet.serve`). [act] runs on the window's own pool and may block (a stalled
 *  program holds that one call, which stays "sent · waiting" on the page: no timeout). A throw is answered
 *  `Refused(<its message>, "program")`. */
fun interface ProgramHandler {
    fun act(op: Op, peer: Peer): ProgramResult
}

/** What a program op's call gave (decisions A.7: Sent or Refused only; no `Seen` watcher in S1-2). */
sealed class ProgramResult {
    /** The call returned; [result] is what it made (a small object: a name, a path, an upload id, a job id). */
    data class Sent(val result: JsonElement? = null) : ProgramResult()

    /** The program refused, in its own words ([reason]); [code] `program` unless the handler names its own. */
    data class Refused(val reason: String, val code: String = Answer.PROGRAM) : ProgramResult()

    /** The wire's answer. */
    val answer: Answer get() = when (this) {
        is Sent -> Answer.Sent
        is Refused -> Answer.Refused(reason, code)
    }
}
