package wm.damage.core.windows.files

import java.nio.file.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.OpQueue
import wm.damage.core.app.Peer
import wm.damage.core.app.ProgramHandler
import wm.damage.core.app.ProgramResult
import wm.damage.core.web.Uploads

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4; `CONTRACT.md` §11.1, §12): Files on the PC, made once per process by both host
 * builders (`runShell` and `hostOnly`): the one job slot, the provider behind it that every PC caller receives
 * ([slotted]: the PC's glasses, `FilesService` for the phone, the web's ops), the core on the PC record (its own writes
 * — the cut's clear after a paste — as device [DEVICE]), the PC-served scopes, the program ops, the blobs and Main's
 * card. A glasses presenter on this PC reads Files' records through [glassesSource]. The slot's supervisor
 * ([FilesJobs.supervise]) runs on the record's pacing tick.
 */
class FilesPc(
    private val host: AppHost,
    /** The PC's own filesystem provider (`LocalFilesProvider`). */
    raw: FilesProvider,
    val booksDir: String,
    /** The uploads the web listener receives (`Uploads`); null = this host takes none. */
    uploads: Uploads?,
    /** The desktop's thumbnail maker (AWT); null = no thumbnails. */
    thumbs: ((ByteArray, Int) -> ByteArray?)?,
) {
    val jobs: FilesJobs = FilesJobs()
    val core: FilesCore = FilesCore({ raw.locations() }, jobs, { booksDir })
    /** The core's own source on the PC record: the shared rows the scopes sort by, and the clipboard's clear. */
    val coreSource: LocalWindowSource = LocalWindowSource(core, HostBackend(host, DEVICE, OpQueue.newStream()))
    val slotted: SlottedFiles = SlottedFiles(raw, jobs, onMoved = { src -> coreSource.act(FilesCore.clearCutDraft(src)) })
    /** The lane Files is served on ([serveOn]): its held scopes say which folders a job's end reads again (L1 F10). */
    @Volatile private var net: AppNet? = null
    val scopes: FilesScopes = FilesScopes(core, slotted, jobs, { coreSource.state() }, { booksDir },
        held = { net?.held() ?: emptySet() })
    val program: FilesProgram = FilesProgram(slotted, uploads)
    val blobs: FilesBlobs = FilesBlobs(slotted, thumbs)

    init {
        slotted.pc = this
        host.addTickListener(jobs::supervise)
    }

    fun card(): JsonObject = filesCard(core, jobs)

    /** Files' parts on [net] (before the web listener starts, so no page asks first). */
    fun serveOn(net: AppNet) {
        this.net = net
        net.serve(FilesCore.WINDOW, scopes, program, ::card)
    }

    /** A source on the PC record for a glasses presenter (device `local`, a stream of its own). */
    fun glassesSource(device: String = Peer.LOCAL.device): LocalWindowSource =
        LocalWindowSource(core, HostBackend(host, device, OpQueue.newStream()))

    companion object {
        /** The device the core writes as on the PC record (as the notice record's raiser does). */
        const val DEVICE = "pc"
    }
}

/**
 * Files' program ops from the `app` lane (the spec's §4.1 table; decisions A.7, A.15): each into the filesystem, never
 * queued. A slot action takes the PC's one slot now (refused with today's words while another runs) and runs as a job
 * after its answer, `Sent{job}`; the page reads its end from the slot. `upload` answers `Sent{upload, job}` and holds
 * the slot until its body ends. An upload never replaces a file: a name that exists is placed under the first free
 * numbered name (`Uploads`).
 */
class FilesProgram(private val files: SlottedFiles, private val uploads: Uploads?) : ProgramHandler {
    override fun act(op: Op, peer: Peer): ProgramResult = try {
        when (op.action) {
            FilesCore.OPEN_ON_PC -> {
                files.openOnPc(one(op))
                ProgramResult.Sent()
            }
            FilesCore.PASTE -> {
                val dest = one(op)
                val verb = param(op, "verb")
                val src = abs(param(op, "path"), "the clipboard's path")
                require(verb == "copy" || verb == "cut") { "a paste is of a copy or a cut, not '$verb'" }
                job(files.start("Paste '${SlottedFiles.name(src)}'", if (verb == "cut") "moving" else "copying") { h ->
                    SlottedFiles.pathAnswer(if (verb == "cut") files.raw.moveCounted(src, dest, h.asProgress).also { files.moved(src) }
                        else files.raw.copyCounted(src, dest, h.asProgress))
                })
            }
            FilesCore.RENAME -> {
                val path = one(op)
                val name = param(op, "name")
                job(files.start("Rename '${SlottedFiles.name(path)}'", "renaming") { SlottedFiles.pathAnswer(files.raw.rename(path, name)) })
            }
            FilesCore.MKDIR -> {
                val dir = one(op)
                val name = param(op, "name")
                job(files.start("New folder '$name'", "creating") { SlottedFiles.pathAnswer(files.raw.mkdir(dir, name)) })
            }
            FilesCore.TRASH -> {
                val paths = many(op).map { abs(it, "a path") }
                job(files.start(label("Move to Trash", paths.map(SlottedFiles::name)), "trashing") { h ->
                    each(paths, "moved to the trash", h) { files.raw.trash(it); null }
                })
            }
            FilesCore.RESTORE -> {
                val ids = many(op)
                job(files.start(label("Restore", trashNames(ids)), "restoring") { h ->
                    each(ids, "restored", h) { "to ${files.raw.restore(it)}" }
                })
            }
            FilesCore.PURGE -> {
                val ids = many(op)
                job(files.start(label("Delete forever", trashNames(ids)), "deleting") { h ->
                    each(ids, "deleted forever", h) { files.raw.purge(it); null }
                })
            }
            FilesCore.STATS -> {
                val path = one(op)
                job(files.start("Stats '${SlottedFiles.name(path)}'", "counting") { h ->
                    h.endAfter(FilesJobs.READ_ONLY_MS)                 // writes nothing: the supervisor may end it
                    val st = files.raw.stat(path)
                    val size = if (st.dir) files.raw.duCounted(path, h.asProgress) else st.size
                    buildJsonObject {
                        put("size", size); put("mtime", st.mtimeMs); put("mode", st.mode); put("owner", st.owner)
                        put("dir", st.dir); put("text", fmtBytes(size))
                    }
                })
            }
            FilesCore.UPLOAD -> upload(op, peer)
            else -> ProgramResult.Refused("Files has no program op '${op.action}'")
        }
    } catch (e: FilesJobs.Busy) {
        ProgramResult.Refused(files.jobs.busyLine(e.holder))
    } catch (e: IllegalArgumentException) {
        ProgramResult.Refused(e.message ?: "the op's targets or parameters are not Files'")
    }

    private fun upload(op: Op, peer: Peer): ProgramResult {
        val u = uploads ?: return ProgramResult.Refused("this PC takes no uploads")
        val dest = one(op)
        val file = op.params["file"] as? JsonObject ?: throw IllegalArgumentException("an upload names its file: {name, size}")
        val name = (file["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("an upload names its file")
        val size = (file["size"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("an upload gives its size")
        val conn = (op.id as? OpId.Conn)?.conn ?: throw IllegalArgumentException("an upload is an op of a connection")
        files.raw.refuseTrashStore(dest, "an upload")
        val h = files.jobs.begin("Upload '$name'", "waiting for the file")
        val id = try {
            u.mint(peer.device, conn, Path.of(dest), name, size) { end ->
                when (end) {
                    is Uploads.End.Done -> h.done(buildJsonObject {
                        put("text", "${end.path.fileName} (${fmtBytes(end.bytes)}) is on the PC")
                        put("path", end.path.toString())
                    })
                    is Uploads.End.Failed -> h.failed(end.reason)
                }
            }
        } catch (e: Exception) {
            h.failed(e.message ?: "the upload could not be begun")
            throw e
        }
        // L1: a ticket whose body never begins (its page went before the PUT) is ended by the supervisor
        h.endAfter(FilesJobs.UPLOAD_WAIT_MS) { u.cancel(id) }
        return ProgramResult.Sent(buildJsonObject { put("upload", id); put("job", h.id) })
    }

    private fun job(id: String) = ProgramResult.Sent(buildJsonObject { put("job", id) })

    private fun label(what: String, names: List<String>) =
        if (names.size == 1) "$what '${names[0]}'" else "$what · ${names.size} items"

    /** The trash entries [ids] name, by their names (an id the trash no longer holds stays an id). */
    private fun trashNames(ids: List<String>): List<String> {
        val byId = try { files.raw.trashList().associate { it.id to it.name } } catch (e: Exception) { emptyMap() }
        return ids.map { byId[it] ?: it }
    }

    /** [f] over each of [items] (its detail for one item, or null); one that fails does not stop the rest, and the job
     *  fails naming each that failed. */
    private fun each(items: List<String>, did: String, h: FilesJobs.Handle, f: (String) -> String?): JsonElement {
        val failed = ArrayList<String>()
        var detail: String? = null
        for ((i, it) in items.withIndex()) {
            try {
                detail = f(it)
            } catch (e: Exception) {
                failed += "${SlottedFiles.name(it)}: ${e.message ?: e::class.simpleName}"
            }
            h.progress(i + 1L, items.size.toLong(), did)
        }
        if (failed.isNotEmpty()) throw IllegalStateException("${items.size - failed.size} of ${items.size} $did; not: ${failed.joinToString("; ")}")
        return SlottedFiles.words(if (items.size == 1) listOfNotNull(did, detail).joinToString(" ") else "${items.size} items $did")
    }

    companion object {
        private fun abs(p: String, what: String): String {
            require(p.startsWith("/") && '\u0000' !in p) { "$what '$p' is not an absolute path on the PC" }
            return p
        }

        private fun one(op: Op): String {
            require(op.targets.size == 1) { "${op.action} names one target (${op.targets.size} given)" }
            return abs(op.targets[0], "the target")
        }

        private fun many(op: Op): List<String> {
            require(op.targets.isNotEmpty()) { "${op.action} names at least one target" }
            return op.targets
        }

        private fun param(op: Op, k: String): String =
            (op.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw IllegalArgumentException("${op.action} needs its '$k'")
    }
}
