package wm.damage.core.windows.files

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Admit
import wm.damage.core.app.ApplyRule
import wm.damage.core.app.Draft
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.shell.ShellSettings

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1; `CONTRACT.md` §3.1, §4, §10.4, §12's Files row; decisions A.15): Files' core.
 * Its own records, shared by every interface: the clipboard `clip` (`{verb: copy|cut, path}` or null), the `sort`
 * (`name|size|mtime`) and `hidden` (bool) rows, and the glasses' `Size` row — registers expecting the value seen. Its
 * actions with their kinds and levels (Files' minimum is full: listing, reading and writing alike, C§10.4; no action
 * sits below it), the ops into the filesystem (never queued), and the description `desc/files`. The PC serves the rest
 * (`FilesScopes`): locations, a folder a page at a time, the trash, text chunks, PDF facts, thumbnails, the job slot.
 * Per interface and local (in `window.files`, unsynced): level, cursors, location, folder, the viewer. No pixels here.
 */
class FilesCore(
    /** The locations now (a filesystem read; called on the describing thread). */
    private val locations: () -> List<FLocation> = { emptyList() },
    /** The PC's job slot; null where no slot lives (the phone, a tool). */
    private val jobs: FilesJobs? = null,
    /** The books folder (an EPUB or text under it opens in Reader). */
    private val booksDir: () -> String = { "" },
) : WindowCore {
    override val window: String = WINDOW

    /** The locations the last description read (Main's card shows them without a filesystem read of its own). */
    @Volatile var lastLocations: List<FLocation>? = null
        private set

    override fun describe(state: WindowState): JsonObject = buildJsonObject {
        put("id", WINDOW)
        put("name", "Files")
        put("icon", "folder")
        put("min", Levels.FULL)
        put("title", "Files")
        val (locs, why) = try {
            locations().also { lastLocations = it } to null
        } catch (e: Exception) {
            emptyList<FLocation>() to "the locations could not be read: ${e.message ?: e::class.simpleName}"
        }
        put("availability", if (why == null) "live" else "unavailable")
        why?.let { put("reason", it) }
        val home = locs.firstOrNull { it.kind == "home" }?.path ?: ""
        put("home", home)
        put("booksDir", booksDir())
        put("locations", buildJsonArray { for (l in locs) add(location(l)) })
        put("jobs", jobs?.json() ?: buildJsonArray { })
        put("actions", buildJsonArray {
            for (a in ACTION_LIST) add(buildJsonObject {
                put("id", a.id); put("label", a.label); put("kind", a.kind); put("level", Levels.FULL)
                put("into", a.into ?: "view")
                if (a.job) put("job", true)
                a.confirm?.let { put("confirm", it) }
            })
        })
        put("settings", buildJsonArray { add(sizeRow(state)) })
    }

    /** Files' levels are PC-served scopes (`FilesScopes`), not record levels. */
    override fun level(state: WindowState, addr: String): JsonObject? = null

    /** The glasses' Size row as the description lists it (the page's settings section; the option ids are the
     *  record's values). */
    private fun sizeRow(state: WindowState): JsonObject = buildJsonObject {
        put("id", SIZE); put("label", "Size (glasses)"); put("scope", "glasses"); put("type", "choice")
        put("level", Levels.FULL); put("action", SET)
        put("value", live(state, SIZE)?.value?.takeIf { validSize(it) } ?: JsonPrimitive(GLOBAL))
        put("options", buildJsonArray {
            add(buildJsonObject { put("id", GLOBAL); put("label", "as Global") })
            for (h in ShellSettings.HEIGHTS) add(buildJsonObject { put("id", h); put("label", "$h") })
        })
    }

    /** One action as the description lists it (C§4.1; kinds as C§12 gives them). */
    data class ActionDesc(val id: String, val label: String, val kind: String, val into: String?, val job: Boolean = false,
                          val confirm: JsonObject? = null)

    companion object {
        const val WINDOW = "files"

        // the record items
        const val CLIP = "clip"
        const val SORT = "sort"
        const val HIDDEN = "hidden"
        /** The glasses' Files height: [GLOBAL] or one of the four sizes. */
        const val SIZE = "glasses.size"
        const val GLOBAL = "global"
        val SORTS = listOf("name", "size", "mtime")

        // the record actions (registers; ⏸ — they apply at once and queue away)
        const val COPY = "copy"
        const val CUT = "cut"
        const val SORT_ROW = "sort"
        const val HIDDEN_ROW = "hidden"
        const val SET = "set"
        /** A register from before Files' conversion, judged by its old stamp (Reader's precedent: the position kind). */
        const val MIGRATE = "migrate"

        // the program ops (never queued; `(conn, k)`): into the filesystem
        const val OPEN_ON_PC = "openOnPc"
        const val PASTE = "paste"
        const val RENAME = "rename"
        const val MKDIR = "mkdir"
        const val UPLOAD = "upload"
        const val TRASH = "trash"
        const val RESTORE = "restore"
        const val PURGE = "purge"
        const val STATS = "stats"

        val PROGRAM: Set<String> = setOf(OPEN_ON_PC, PASTE, RENAME, MKDIR, UPLOAD, TRASH, RESTORE, PURGE, STATS)

        /** Files' level for every action it knows (C§10.4: Files is full as a whole). Null: no such action. */
        fun levelOf(action: String): String? =
            if (action in PROGRAM || action in setOf(COPY, CUT, SORT_ROW, HIDDEN_ROW, SET, MIGRATE, "open", "openInReader", "download"))
                Levels.FULL else null

        /** Files' record actions as the judge sees them (C§2.4). */
        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                COPY, CUT, SORT_ROW, HIDDEN_ROW, SET -> ActionSpec(Kind.REGISTER)
                MIGRATE -> ActionSpec(Kind.POSITION)
                else -> null
            }
        }

        /** The actions a presenter offers, in the spec's §4.1 order (A.15: several selected → trash, restore, delete
         *  forever, download; the page keeps that list). */
        val ACTION_LIST = listOf(
            ActionDesc("open", "Open", "v", null),
            ActionDesc("openInReader", "Open in Reader", "v", null),
            ActionDesc("download", "Download", "v", null),
            ActionDesc(OPEN_ON_PC, "Open on PC", "r", "program"),
            ActionDesc(COPY, "Copy", "r", "record"),
            ActionDesc(CUT, "Cut", "r", "record"),
            ActionDesc(SORT_ROW, "Sort", "r", "record"),
            ActionDesc(HIDDEN_ROW, "Hidden files", "r", "record"),
            ActionDesc(PASTE, "Paste", "i", "program", job = true),
            ActionDesc(RENAME, "Rename", "i", "program", job = true),
            ActionDesc(MKDIR, "New folder", "i", "program", job = true),
            ActionDesc(UPLOAD, "Upload", "i", "program", job = true),
            ActionDesc(TRASH, "Move to Trash", "r", "program", job = true, confirm = buildJsonObject { put("double", false) }),
            ActionDesc(RESTORE, "Restore", "r", "program", job = true),
            ActionDesc(PURGE, "Delete forever", "i", "program", job = true, confirm = buildJsonObject { put("double", true) }),
            ActionDesc(STATS, "Stats", "job", "program", job = true),
        )

        /** Files' rule at apply time (L1 F2: no check of the rows' values — every reader drops a value that is not
         *  one): the core's clear of a cut ([clearCutDraft]) changes nothing unless the clipboard still holds that
         *  cut, so a clip set meanwhile stands. */
        val RULE = ApplyRule { rec, op ->
            if (op.window != WINDOW || op.action != CUT || op.result != JsonNull) return@ApplyRule null
            val src = (op.params["src"] as? JsonPrimitive)?.contentOrNull
            val held = rec.item(wm.damage.core.app.Record.key(WINDOW, CLIP))?.takeIf { !it.gone }?.value
            if (src != null && clipValue(held) == ("cut" to src)) null else Admit.Keep
        }

        // ---- reading the record ----

        private fun live(state: WindowState, name: String): Item? = state.itemNamed(name)?.takeIf { !it.gone }

        /** A clipboard value as (verb, path), or null when [v] is not one. */
        fun clipValue(v: JsonElement?): Pair<String, String>? {
            val o = v as? JsonObject ?: return null
            val verb = (o["verb"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val path = (o["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return if ((verb == "copy" || verb == "cut") && path.startsWith("/")) verb to path else null
        }

        fun clipJson(verb: String, path: String): JsonObject = buildJsonObject { put("verb", verb); put("path", path) }

        fun sortValue(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in SORTS }

        fun validSize(v: JsonElement?): Boolean {
            val p = v as? JsonPrimitive ?: return false
            return if (p.isString) p.content == GLOBAL else p.intOrNull in ShellSettings.HEIGHTS
        }

        /** The clipboard the record holds, as (verb, path), or null. */
        fun clipOf(state: WindowState): Pair<String, String>? = clipValue(live(state, CLIP)?.value)

        /** The shared sort, or null (none written: name). */
        fun sortOf(state: WindowState): String? = sortValue(live(state, SORT)?.value)

        fun hiddenOf(state: WindowState): Boolean? =
            (live(state, HIDDEN)?.value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        /** The glasses' height: null = the Global row (or nothing written). */
        fun sizeOf(state: WindowState): Int? =
            (live(state, SIZE)?.value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in ShellSettings.HEIGHTS }

        // ---- the drafts a presenter sends (C§2.4: the result; the latest input wins, L1 C1) ----

        fun clipDraft(verb: String, path: String) = Draft(if (verb == "cut") CUT else COPY, listOf(CLIP), clipJson(verb, path))

        /** S1-2 R0 §3: the core's clear after a cut's paste landed — naming the cut it took ([RULE]: a clip changed
         *  meanwhile stands and nothing is said; today's `clip == took`). */
        fun clearCutDraft(src: String) = Draft(CUT, listOf(CLIP), JsonNull, buildJsonObject { put("src", src) })

        fun sortDraft(key: String) = Draft(SORT_ROW, listOf(SORT), JsonPrimitive(key))

        fun hiddenDraft(on: Boolean) = Draft(HIDDEN_ROW, listOf(HIDDEN), JsonPrimitive(on))

        fun sizeDraft(height: Int?) = Draft(SET, listOf(SIZE), height?.let { JsonPrimitive(it) } ?: JsonPrimitive(GLOBAL))

        /** A location as `desc/files` lists it (C§12: `{path, label, kind, total, free}`). */
        fun location(l: FLocation): JsonObject = buildJsonObject {
            put("path", l.path); put("label", l.label); put("kind", l.kind)
            put("total", l.totalBytes); put("free", l.freeBytes)
        }
    }
}
