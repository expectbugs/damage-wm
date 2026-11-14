package wm.damage.core.bastion

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.net.WinService
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This is the PC's side of the `bastion` window channel (build W, R6 P6; the spec's
 * §2(b)): the phone asks it for the alerts the PC's reader of his home hub has raised, acknowledges
 * them, and hears each change as it happens. Registered in BOTH PC host builders' `win` maps on the
 * content port (`desktop/…/Main.kt`: `runShell` and `hostOnly`).
 *
 * Ops:
 * - `table {since, epoch}` → [AlertStore.answer] with the watcher's state line: the table, the
 *   events since, `truncated` when the client's `since` is outside what the ring answers for, or
 *   its epoch is another store's (§2(b)'s rows; [AlertStore] decides them under its lock).
 * - `ack {id, episode}` → [AlertStore.ack]: Damage's own store only, NEVER a request to the hub.
 * - Package F (W-decisions G.1–G.3): a `table` ask may carry `presence {word, fixAgeMs?}` — the phone's own word
 *   (`home` / `away` / `unknown`) and the age of the fix behind it, NOTHING else — handed to [presence]
 *   ([PhonePresence.phoneReport]); a report this host cannot use is said once per kind and ignored, and the table is
 *   answered as usual (the alerts never wait on presence). Every `table` answer carries `bus`, package E's path
 *   signal ([PhonePresence.onBusNetwork]), so the phone learns where the bus is.
 *
 * Pushes: every store event is queued, in `seq` order, into one unbounded channel from the store's
 * listener (called under the store's lock, so it must not block: a queue write never does), and ONE
 * sender coroutine sends op `event` `{epoch, event}` to every attached driver. A stuck socket blocks
 * that sender, never the store or the poll; a failed push ends that driver's channel loudly
 * (`WinNet`'s push), and its reconnect asks `table` again — a gap is recovered as state.
 */
class BastionService(
    private val store: AlertStore,
    /** The watcher's state line ([BastionWatcher.stateLine]), carried in every `table` answer. */
    private val stateLine: () -> String,
    scope: CoroutineScope,
    /** `damage.phone` on this PC (package E; both host builders pass theirs): the phone's reports go to it and its
     *  path signal comes back in every answer. Null: none (the tests' hosts), `bus` absent from the answer. */
    private val presence: PhonePresence? = null,
) : WinService {

    private val pushers = CopyOnWriteArrayList<WinService.Push>()
    private val queue = Channel<AlertEvent>(Channel.UNLIMITED)

    private val listener: (AlertEvent) -> Unit = { ev ->
        val r = queue.trySend(ev)
        if (!r.isSuccess) Log.e(TAG, "alert event ${ev.seq} (${ev.kind.wire} ${ev.id}) not queued for the drivers: " +
            "the sender has stopped (${r.exceptionOrNull()?.message ?: "closed"}); a driver's next table ask carries it")
    }

    private val sender: Job = scope.launch(Dispatchers.IO) {
        for (ev in queue) {
            val args = buildJsonObject {
                put("epoch", store.epoch)
                put("event", ev.toJson())
            }
            // WinNet's push never throws to us: a write failure ends that driver's channel, said there
            for (p in pushers) p.send(OP_EVENT, args)
        }
    }

    init {
        store.addListener(listener)
    }

    override fun attached(push: WinService.Push) {
        pushers.addIfAbsent(push)
    }

    override fun detached(push: WinService.Push) {
        pushers.remove(push)
    }

    override fun request(op: String, args: JsonObject): WinService.Answer = when (op) {
        OP_TABLE -> {
            val since = long(args, "since") ?: 0L
            val epoch = long(args, "epoch") ?: 0L
            args["presence"]?.let { takePresence(it) }
            val a = store.answer(since, epoch)
            WinService.Answer(a.copy(stateLine = stateLine(), bus = presence?.onBusNetwork()).toJson())
        }
        OP_ACK -> {
            val id = (args["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("ack: missing 'id'")
            val episode = long(args, "episode") ?: throw IllegalArgumentException("ack: missing 'episode'")
            val r = store.ack(id, episode)
            if (r.ok) Log.i(TAG, "alert $id episode $episode acknowledged by a driver")
            else Log.i(TAG, "ack of $id episode $episode answered: ${r.reason} (state ${r.state.wire})")
            WinService.Answer(r.toJson())
        }
        else -> throw IllegalArgumentException("unknown bastion op '$op'")
    }

    private val presenceSaid: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The phone's presence report (package F). Only `word` and `fixAgeMs` are read; any other key is ignored and
     *  never written anywhere. A report this host cannot use is said once per kind — its text is NOT (it came from
     *  the phone and is not this host's to copy) — and the table is answered as usual. */
    private fun takePresence(el: JsonElement) {
        val o = el as? JsonObject
        val word = (o?.get("word") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ageEl = o?.get("fixAgeMs")
        val age = (ageEl as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
        val why = when {
            o == null -> "it is not an object"
            word == null || word !in PhonePresence.WORDS -> "its word is not home, away or unknown"
            ageEl != null && ageEl !is JsonNull && age == null -> "its fixAgeMs is not a whole number of ms"
            presence == null -> "this host keeps no damage.phone value"
            else -> null
        }
        if (why != null) {
            if (presenceSaid.add(why)) Log.w(TAG, "a presence report from the phone was ignored: $why " +
                "(said once per kind; the table is answered as usual)")
            return
        }
        presence?.phoneReport(word!!, age)
    }

    /** A number argument; absent → null; present but not a whole number → refused in-band. */
    private fun long(args: JsonObject, key: String): Long? {
        val el = args[key] ?: return null
        val p = el as? JsonPrimitive ?: throw IllegalArgumentException("'$key' is not a number")
        return p.longOrNull ?: throw IllegalArgumentException("'$key' is not a whole number")
    }

    /** Stops the sender and the listener (tests; a host runs it for the life of the process). */
    fun close() {
        store.removeListener(listener)
        queue.close()
        sender.cancel()
    }

    companion object {
        private const val TAG = "bastion"
        const val WIN_ID = "bastion"
        const val OP_TABLE = "table"
        const val OP_ACK = "ack"
        const val OP_EVENT = "event"
    }
}
