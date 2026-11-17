package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Expect
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.RecordAlerts

/*
 * Build S1-1, package P1: the rig the record's tests share — an `AppHost` over a temp directory with a made-up window
 * `t`, a clock the test moves, a log force the test can make fail, a saver the test can hold, and the alerts and
 * journal notes the record says. Nothing here touches the real data directory.
 */

/** The made-up window's actions, one per queued kind (L1 C4: register, position, removal). */
internal val TEST_ACTIONS = ActionTable { w, a ->
    if (w != "t") null else when (a) {
        "set" -> ActionSpec(Kind.REGISTER)
        "place" -> ActionSpec(Kind.POSITION)
        "drop" -> ActionSpec(Kind.REMOVE)
        else -> null
    }
}

internal fun j(s: String): JsonElement = JsonPrimitive(s)

internal fun op(
    device: String, stream: String, n: Long, action: String, targets: List<String>,
    result: JsonElement = JsonNull, expect: Expect = Expect.Any,
) = Op(device, OpId.Queued(stream, n), "t", action, targets, result, expect)

/** A saver that holds every write until the test runs it. */
internal class HeldWriter : Executor {
    private val queue = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) = synchronized(queue) { queue.addLast(command) }
    val waiting: Int get() = synchronized(queue) { queue.size }
    fun runAll() {
        while (true) (synchronized(queue) { queue.removeFirstOrNull() } ?: return).run()
    }
}

internal class RecordRig(val dir: Path = Files.createTempDirectory("damage-record")) {
    var now = 1_000_000L
    val said = CopyOnWriteArrayList<String>()
    val alerts = object : RecordAlerts {
        override fun raise(id: String, text: String) { said += "raise $id: $text" }
        override fun clear(id: String) { said += "clear $id" }
    }
    val journal: Path = dir.resolve("journal.jsonl")
    @Volatile var syncFails = false
    val forced = AtomicInteger()
    var storeWrite: ((Path, String) -> Unit)? = null
    var storeWriter: Executor = Executor { it.run() }
    var setAsideBytes = AppHost.LOG_SET_ASIDE_BYTES
    private val hosts = ArrayList<AppHost>()

    val logFile: Path get() = dir.resolve(AppHost.LOG_FILE)
    val recordFile: Path get() = dir.resolve(AppHost.RECORD_FILE)
    val seqFile: Path get() = dir.resolve(AppHost.SEQ_FILE)

    /** A host over the rig's directory (not opened). */
    fun host(): AppHost = AppHost(
        dir, TEST_ACTIONS, CoreExecutor("damage-core-test"), { now }, alerts, journal,
        logSync = { ch ->
            forced.incrementAndGet()
            if (syncFails) throw IOException("no space left on device (test)")
            ch.force(true)
        },
        storeWrite = storeWrite, storeWriter = storeWriter, setAsideBytes = setAsideBytes,
    ).also { hosts += it }

    /** A host, opened. */
    fun opened(): AppHost = host().also { it.open() }

    /** Sends [o] and returns its answer, once the executor has handled it. */
    fun ask(h: AppHost, o: Op): Answer {
        val got = ArrayList<Answer>()
        h.act(o) { got += it }
        h.executor.call {}
        if (got.size != 1) throw AssertionError("op ${o.id} got ${got.size} answers, not one: $got")
        return got[0]
    }

    /** The pacing tick, and the save's completion queued behind it. */
    fun tick(h: AppHost) {
        h.tick()
        h.executor.call {}
        h.executor.call {}
    }

    fun item(h: AppHost, id: String): Item? = h.read("t").third.items["rec.t.$id"]

    fun notes(): List<String> =
        if (Files.exists(journal)) Files.readAllLines(journal).filter { "\"kind\":\"record\"" in it } else emptyList()

    /** Ends [h] as a process end would leave it: no save, the log closed. */
    fun stop(h: AppHost) {
        if (!hosts.remove(h)) return
        h.close()
        h.executor.shutdown()
    }

    fun cleanUp() {
        for (h in hosts.toList()) stop(h)
        dir.toFile().walkTopDown().forEach { it.setWritable(true) }
        dir.toFile().deleteRecursively()
    }
}
