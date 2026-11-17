package wm.damage.core.app

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P2 (`CONTRACT.md` §9.1–§9.3; the spec's §2 P2 and §4 "Versioning"; dec. A.4): the messages of
 * the `app` lane on the content port, in its framing (a 4-byte length, then one JSON object). The lane's own hello is
 * the frame that asks for the lane, `{"t":"app","proto":2,"stream":…,"contracts":{…}}` — ONE frame, so an old PC
 * answers it with its one in-band "unknown request" and the stream stays aligned (`Content.kt`'s cross-version law);
 * the content port's own hello before it stays `proto: 1`.
 *
 * Client → PC: `sub {scope, epoch?, seq?}` · `unsub {scope}` · `op {op}` · `ping {k}` · `end {reason}`.
 * PC → client: `welcome` · `snap {scope, epoch, seq, items}` · `chg {scope, epoch, seq, patches}` ·
 * `ans {stream, n | conn, k | scope, answer}` · `pong {k}` · `end {reason}`.
 * A patch is `{addr, set}` (an item's whole entry): entries are absolute, never removed (a removal is the entry's
 * `gone`), so a patch applied twice changes nothing.
 */

/** A client's place in the record's order (C§9.3): the store's epoch and the last `seq` it holds. */
data class Cursor(val epoch: Long, val seq: Long)

/** Who a connection is (C§10): the device the credential names and its level. [approval]: for a device-table row, the
 *  approval its credential was read at (the row's secret hash, never the secret), which the welcome checks the row
 *  still holds (fix round 2, ruling 2.4); null for the token's and the local secret's holders. */
data class Peer(val device: String, val level: String, val approval: String? = null) {
    companion object {
        const val FULL = "full"
        /** The shared token's holder: his phone (C§10.9's table). */
        val PHONE = Peer("phone", FULL)
        /** The local secret's holder on loopback: the PC's own command line and companion (C§10.6). */
        val LOCAL = Peer("local", FULL)
    }
}

/** The lane's hello, as the PC reads it. */
data class LaneHello(val proto: Int, val stream: String, val contracts: Map<String, Int>)

/** `welcome`: who this connection is, the PC's clock, the record's epoch and `seq`, and the PC's contract versions. */
data class Welcome(
    val proto: Int,
    val device: String,
    val level: String,
    val conn: Long,
    val pcClock: Long,
    val epoch: Long,
    val seq: Long,
    val contracts: Map<String, Int>,
    /** The page bundle a web page runs (C§9.5: `version`, `hash`, `protoRange`); null on the content port. */
    val bundle: JsonObject? = null,
)

/** `snap`: a scope's whole state at `(epoch, seq)` — the entries' effects up to and including `seq`. */
data class Snap(val scope: String, val epoch: Long, val seq: Long, val state: Effect)

/** `chg`: one published change, its entries limited to the scope. A client ignores one at or below its cursor. */
data class Chg(val scope: String, val epoch: Long, val seq: Long, val patches: Effect)

/** `ans`: an op's answer ([id]), or a subscription's refusal ([scope]); [id] and [scope] null = an op that did not
 *  decode far enough to name itself. [at] (P3): the record's place once the op is in it — the op's own line, or later
 *  — so a client knows which state already holds the op's effect: any `snap` or `chg` at or past it (one epoch). */
data class Ans(
    val id: OpId?,
    val scope: String?,
    val answer: Answer,
    val at: Cursor? = null,
    /** S1-2 Q2 (the spec's §2.4): what a program op's call made (an added torrent's name, an upload id, a job id),
     *  carried in the answer object as `result`; only beside [Answer.Sent]. */
    val result: JsonElement? = null,
)

object AppWire {
    /** The lane's protocol (dec. A.4): carried in the lane's hello, never in the content port's. */
    const val PROTO = 2
    /** The content port's lane name, and the `t` of the lane's hello. */
    const val LANE = "app"
    /** The scope of every shared window's records (C§9.6). */
    const val RECORD = "record"
    const val MAX_FRAME = 4 shl 20

    /** `end` reasons this build sends. */
    const val END_SLOW = "slow"
    const val END_PROTO = "proto"
    const val END_NOT_OPEN = "not open"
    const val END_SHUTTING_DOWN = "shutting down"
    /** Build S1-1 P6 (C§10.3, C§10.8). */
    const val END_REVOKED = "revoked"
    const val END_REPLACED = "replaced"
    const val END_DENIED = "denied"

    private val json = Json { ignoreUnknownKeys = true }

    // ---- frames ----

    fun encode(o: JsonObject): ByteArray = o.toString().toByteArray(Charsets.UTF_8)

    /** Writes one frame and flushes. The caller holds whatever lock its stream needs. */
    fun write(out: DataOutputStream, frame: ByteArray) {
        out.writeInt(frame.size)
        out.write(frame)
        out.flush()
    }

    /** Reads one frame; a length out of range or a frame that is not a JSON object throws (the connection ends). */
    fun read(inp: DataInputStream): JsonObject {
        val n = inp.readInt()
        require(n in 1..MAX_FRAME) { "app frame length $n out of range" }
        val b = ByteArray(n)
        inp.readFully(b)
        return json.parseToJsonElement(b.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("an app frame is not a JSON object")
    }

    /** One frame's text, as the content host hands the lane's first frame over. */
    fun parse(line: String): JsonObject = json.parseToJsonElement(line) as? JsonObject
        ?: throw IllegalArgumentException("an app frame is not a JSON object")

    fun type(f: JsonObject): String = (f["t"] as? JsonPrimitive)?.contentOrNull ?: ""

    // ---- client → PC ----

    fun hello(stream: String, contracts: Map<String, Int>, proto: Int = PROTO): JsonObject = buildJsonObject {
        put("t", LANE)
        put("proto", proto)
        put("stream", stream)
        put("contracts", versions(contracts))
    }

    fun parseHello(f: JsonObject): LaneHello = LaneHello(
        proto = (f["proto"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("hello: proto"),
        stream = OpJson.str(f, "stream"),
        contracts = versions(f["contracts"] as? JsonObject ?: throw IllegalArgumentException("hello: contracts")),
    )

    fun sub(scope: String, cursor: Cursor?): JsonObject = buildJsonObject {
        put("t", "sub")
        put("scope", scope)
        cursor?.let { put("epoch", it.epoch); put("seq", it.seq) }
    }

    /** The cursor a `sub` names, or null (first contact). */
    fun cursorOf(f: JsonObject): Cursor? {
        val e = OpJson.longOf(f["epoch"]) ?: return null
        val s = OpJson.longOf(f["seq"]) ?: return null
        return Cursor(e, s)
    }

    fun unsub(scope: String): JsonObject = buildJsonObject { put("t", "unsub"); put("scope", scope) }

    fun op(o: Op): JsonObject = buildJsonObject { put("t", "op"); put("op", OpJson.op(o)) }

    fun ping(k: Long): JsonObject = buildJsonObject { put("t", "ping"); put("k", k) }

    fun pong(k: Long): JsonObject = buildJsonObject { put("t", "pong"); put("k", k) }

    fun end(reason: String, detail: String? = null): JsonObject = buildJsonObject {
        put("t", "end")
        put("reason", reason)
        detail?.let { put("detail", it) }
    }

    fun str(f: JsonObject, k: String): String = OpJson.str(f, k)

    fun num(f: JsonObject, k: String): Long = OpJson.num(f, k)

    // ---- PC → client ----

    fun welcome(w: Welcome): JsonObject = buildJsonObject {
        put("t", "welcome")
        put("proto", w.proto)
        put("device", w.device)
        put("level", w.level)
        put("conn", w.conn)
        put("pcClock", w.pcClock)
        put("epoch", w.epoch)
        put("seq", w.seq)
        put("contracts", versions(w.contracts))
        w.bundle?.let { put("bundle", it) }
    }

    fun parseWelcome(f: JsonObject): Welcome = Welcome(
        proto = num(f, "proto").toInt(),
        device = str(f, "device"),
        level = str(f, "level"),
        conn = num(f, "conn"),
        pcClock = num(f, "pcClock"),
        epoch = num(f, "epoch"),
        seq = num(f, "seq"),
        contracts = versions(f["contracts"] as? JsonObject ?: throw IllegalArgumentException("welcome: contracts")),
        bundle = f["bundle"] as? JsonObject,
    )

    /** A PC-served scope's description (P6: the device table, C§9.2's `snap` of "the description or level"); no
     *  `seq`, as it is outside the record's order. */
    fun desc(scope: String, desc: JsonObject): JsonObject = buildJsonObject {
        put("t", "snap")
        put("scope", scope)
        put("desc", desc)
    }

    /** C§9.2 `pending`: the waiting state of an unapproved page (P6). */
    fun pending(code: String): JsonObject = buildJsonObject { put("t", "pending"); put("code", code) }

    /** C§9.2 `approved`: the single-use claim the waiting page redeems with one request (P6). */
    fun approved(claim: String): JsonObject = buildJsonObject { put("t", "approved"); put("claim", claim) }

    fun snap(s: Snap): JsonObject = buildJsonObject {
        put("t", "snap")
        put("scope", s.scope)
        put("epoch", s.epoch)
        put("seq", s.seq)
        put("items", RecordJson.items(s.state.items))
    }

    fun parseSnap(f: JsonObject): Snap = Snap(
        str(f, "scope"), num(f, "epoch"), num(f, "seq"),
        Effect(RecordJson.items(f["items"] as? JsonObject ?: JsonObject(emptyMap()))),
    )

    fun chg(c: Chg): JsonObject = buildJsonObject {
        put("t", "chg")
        put("scope", c.scope)
        put("epoch", c.epoch)
        put("seq", c.seq)
        put("patches", buildJsonArray {
            for ((k, v) in c.patches.items.toSortedMap()) add(buildJsonObject { put("addr", k); put("set", RecordJson.item(v)) })
        })
    }

    fun parseChg(f: JsonObject): Chg {
        val items = HashMap<String, Item>()
        for (p in f["patches"] as? JsonArray ?: throw IllegalArgumentException("chg: patches")) {
            val o = p as? JsonObject ?: throw IllegalArgumentException("chg: a patch")
            val set = o["set"] as? JsonObject ?: throw IllegalArgumentException("chg: patch ${o["addr"]} has no set")
            items[str(o, "addr")] = RecordJson.item(set)
        }
        return Chg(str(f, "scope"), num(f, "epoch"), num(f, "seq"), Effect(items))
    }

    fun ans(a: Ans): JsonObject = buildJsonObject {
        put("t", "ans")
        when (val id = a.id) {
            is OpId.Queued -> { put("stream", id.stream); put("n", id.n) }
            is OpId.Conn -> { put("conn", id.conn); put("k", id.k) }
            null -> {}
        }
        a.scope?.let { put("scope", it) }
        a.at?.let { put("epoch", it.epoch); put("seq", it.seq) }
        val ans = OpJson.answer(a.answer)
        put("answer", a.result?.let { JsonObject(ans + ("result" to it)) } ?: ans)
    }

    fun parseAns(f: JsonObject): Ans {
        val a = f["answer"] as? JsonObject ?: throw IllegalArgumentException("ans: answer")
        return Ans(
            id = idOf(f),
            scope = (f["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            answer = OpJson.answer(a),
            at = cursorOf(f),
            result = a["result"],
        )
    }

    /** S1-2 Q2 (the spec's §2.3): a `sub`'s viewer need, `{kind: focus | glance | none, paceMs?}`; null = none given. */
    fun needOf(f: JsonObject): Viewers.Need? = (f["need"] as? JsonObject)?.let { Viewers.Need.parse(it) }

    /** The op id an object names (`stream` + `n`, or `conn` + `k`), or null. Never throws. */
    fun idOf(o: JsonObject): OpId? {
        val stream = (o["stream"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val n = OpJson.longOf(o["n"])
        if (stream != null && n != null) return OpId.Queued(stream, n)
        val conn = OpJson.longOf(o["conn"])
        val k = OpJson.longOf(o["k"])
        return if (conn != null && k != null) OpId.Conn(conn, k) else null
    }


    private fun versions(m: Map<String, Int>): JsonObject = buildJsonObject { for ((w, v) in m.toSortedMap()) put(w, v) }

    private fun versions(o: JsonObject): Map<String, Int> = o.mapValues { (w, v: JsonElement) ->
        (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("contracts: $w")
    }

    /** The windows a scope covers on a connection that shares [shared]: `record` = all of them; a window = itself. */
    fun windowsOf(scope: String, shared: Set<String>): Set<String> =
        if (scope == RECORD) shared else if (scope in shared) setOf(scope) else emptySet()

    /** [e]'s entries that belong to [windows]. */
    fun within(e: Effect, windows: Set<String>): Effect {
        if (windows.isEmpty()) return Effect.NONE
        fun mine(k: String) = windows.any { k.startsWith(Record.prefix(it)) }
        return Effect(e.items.filterKeys(::mine))
    }
}
