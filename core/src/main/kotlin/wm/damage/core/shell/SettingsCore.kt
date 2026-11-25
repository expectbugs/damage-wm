package wm.damage.core.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.AppHost
import wm.damage.core.app.Draft
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.Migrate
import wm.damage.core.app.Record
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.motion.ScrollEffects
import wm.damage.core.motion.Transitions
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage S (the lean spec's §4.2): Settings' values on the PC's record. One item per stored field of
 * [ShellSettings] (`rec.settings.<field>`, its value the field's JSON) and one per app's style (`rec.settings.app.<id>`,
 * the [ShellSettings.AppStyle]'s JSON); absent = the default. Actions `set` (one op per changed item) and `migrate`; no
 * apply rule: the merge rule by input time decides. [ROWS] is the one description of every row (its words are the
 * glasses'); [fold], [with] and [diff] carry every field generically over the serializer, so a field added to
 * [ShellSettings] needs only its row here (`SettingsCoreTest` fails without it). No pixels here.
 */
class SettingsCore(private val apps: List<Pair<String, String>> = APPS) : WindowCore {
    override val window: String = WINDOW

    override fun describe(state: WindowState): JsonObject = describe(state, apps)

    override fun level(state: WindowState, addr: String): JsonObject? = null

    /**
     * One described row: [id] the row's name, [label] the glasses' words, [category] where the glasses show it, [scope]
     * `glasses` · `shared` · `phone`, [options] its values with their words in the glasses' order ([range] for a
     * number), [item] the record item it is stored in and, for an app's style, [field] the style's field.
     */
    data class Row(val id: String, val label: String, val category: String, val scope: String,
                   val options: List<Pair<JsonElement, String>> = emptyList(), val range: IntProgression? = null,
                   val set: Boolean = false, val item: String = id, val field: String? = null) {
        val type: String get() = when {
            range != null -> "number"
            set -> "set"
            options.isNotEmpty() && options.all { (it.first as? JsonPrimitive)?.let { p -> !p.isString && p.booleanOrNull != null } == true } -> "bool"
            else -> "choice"
        }
    }

    companion object {
        const val WINDOW = "settings"
        /** The local store's key for the shell's copy (what shows until the source has heard Settings). */
        const val STORE_KEY = "shell.settings"
        /** `rec.settings.app.<windowId>`: one app's style. */
        const val APP = "app."
        private const val APP_STYLES = "appStyles"
        private const val TAG = "settings"

        // ---- actions ----
        /** A row's value (register; one op per changed item). */
        const val A_SET = "set"
        const val MIGRATE = Migrate.ACTION

        fun levelOf(action: String): String? = when (action) {
            A_SET, MIGRATE -> Levels.CONTROL
            else -> null
        }

        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                A_SET -> ActionSpec(Kind.REGISTER)
                MIGRATE -> ActionSpec(Kind.POSITION)
                else -> null
            }
        }

        /** The apps whose style rows exist: every converted window but the notice record and Settings itself, in Main's
         *  order (`Shell.settingsApps`). */
        val APPS: List<Pair<String, String>> by lazy {
            wm.damage.core.app.Windows.ALL.filter { it.id != wm.damage.core.app.Notices.WINDOW && it.id != WINDOW }.map { it.id to it.title }
        }

        // ---- the codec: generic over the serializer's fields ----
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** Every stored item [s] makes, by name, in its full encoding (defaults included): each field but the styles,
         *  then `app.<id>` per style held. */
        fun encode(s: ShellSettings): Map<String, JsonElement> {
            val out = LinkedHashMap<String, JsonElement>()
            for ((k, v) in JSON.encodeToJsonElement(ShellSettings.serializer(), s).jsonObject) if (k != APP_STYLES) out[k] = v
            for ((id, a) in s.appStyles) out[APP + id] = style(a)
            return out
        }

        fun style(a: ShellSettings.AppStyle): JsonElement = JSON.encodeToJsonElement(ShellSettings.AppStyle.serializer(), a)

        private val DEFAULTS: Map<String, JsonElement> = encode(ShellSettings())
        private val DEFAULT_STYLE: JsonElement = style(ShellSettings.AppStyle())

        /** Every stored field's item name but the styles', in [ShellSettings]' order. */
        val FIELDS: List<String> get() = DEFAULTS.keys.toList()

        /** True when [name] is an item this build stores; anything else (a field an older build kept) is ignored. */
        fun known(name: String): Boolean = name in DEFAULTS || (name.startsWith(APP) && name.length > APP.length)

        /** What item [name] reads as when absent or removed. */
        fun defaultOf(name: String): JsonElement? = if (name.startsWith(APP)) DEFAULT_STYLE else DEFAULTS[name]

        /** Item [name]'s value in [state]: its value, or [defaultOf] when absent or removed. */
        fun valueIn(state: WindowState, name: String): JsonElement? =
            state.itemNamed(name)?.takeIf { !it.gone }?.value ?: defaultOf(name)

        /**
         * [s] with [items] over it (item name → value; null = the default), clamped. An item that is not a value of its
         * field is left out and said; an item this build does not store is ignored. The same instance when [items] is
         * empty.
         */
        fun with(s: ShellSettings, items: Map<String, JsonElement?>): ShellSettings {
            if (items.isEmpty()) return s
            val base = LinkedHashMap(JSON.encodeToJsonElement(ShellSettings.serializer(), s).jsonObject).apply { remove(APP_STYLES) }
            val styles = LinkedHashMap(s.appStyles)
            val top = LinkedHashMap<String, JsonElement>()
            for ((name, v) in items) when {
                name.startsWith(APP) && name.length > APP.length -> {
                    val id = name.removePrefix(APP)
                    if (v == null) { styles.remove(id); continue }
                    val a = try { JSON.decodeFromJsonElement(ShellSettings.AppStyle.serializer(), v) } catch (e: Exception) { null }
                    if (a == null) Log.w(TAG, "rec.settings.$name is not an app's style — left out") else styles[id] = a
                }
                name in DEFAULTS -> top[name] = v ?: DEFAULTS.getValue(name)
            }
            val all = decode(base + top) ?: run {
                // one item at a time beside the base: a value that is not one of its field's is left out alone
                val ok = LinkedHashMap(base)
                for ((name, v) in top) {
                    if (decode(ok + (name to v)) != null) ok[name] = v else Log.w(TAG, "rec.settings.$name ($v) is not a value of that field — left out")
                }
                decode(ok) ?: s
            }
            return all.copy(appStyles = styles).clamped()
        }

        private fun decode(o: Map<String, JsonElement>): ShellSettings? =
            try { JSON.decodeFromJsonElement(ShellSettings.serializer(), JsonObject(o)) } catch (e: Exception) { null }

        /** [v] as item [name]'s value in its full encoding, clamped; null when it is not a value of that item (the
         *  migration's reader). */
        fun itemOf(name: String, v: JsonElement): JsonElement? = when {
            name.startsWith(APP) && name.length > APP.length ->
                try { style(JSON.decodeFromJsonElement(ShellSettings.AppStyle.serializer(), v).clamped()) } catch (e: Exception) { null }
            name in DEFAULTS -> decode(DEFAULTS + (name to v))?.clamped()?.let { encode(it)[name] }
            else -> null
        }

        /** The record's items of this window ([items]: full keys) over [base]: absent = [base]'s, removed = the default. */
        fun fold(items: Map<String, Item>, base: ShellSettings = ShellSettings()): ShellSettings {
            val p = Record.prefix(WINDOW)
            val m = LinkedHashMap<String, JsonElement?>()
            for ((k, i) in items) if (k.startsWith(p)) m[k.removePrefix(p)] = if (i.gone) null else i.value
            return with(base, m)
        }

        /** The PC's record's settings (its Quiet hours, read at each raise: `D/Main.kt`). */
        fun onHost(host: AppHost): ShellSettings = fold(host.read(WINDOW).third.items)

        /** One `set` per item [new] changes from [old], compared in their full encoding (defaults included); [at] the
         *  input's time. */
        fun diff(old: ShellSettings, new: ShellSettings, at: Long? = null): List<Draft> {
            val a = encode(old)
            val b = encode(new)
            val out = ArrayList<Draft>()
            for ((name, v) in b) if ((a[name] ?: defaultOf(name)) != v) out += Draft(A_SET, listOf(name), v, at = at)
            for ((name, v) in a) if (name !in b && v != defaultOf(name)) out += Draft(A_SET, listOf(name), defaultOf(name)!!, at = at)
            return out
        }

        // ---- the description ----
        private fun p(v: Int) = JsonPrimitive(v)
        private fun p(v: String) = JsonPrimitive(v)
        private val ON_OFF = listOf(JsonPrimitive(true) to "on", JsonPrimitive(false) to "off")
        private fun words(vs: List<String>) = vs.map { p(it) to it }
        private const val GLOBAL = "Global"
        private fun anim(tier: String) = "Animations · $tier"

        /** Stored fields with no row on any surface (his word, 2026-11-23): `Battery alert` until its pulse exists. */
        val HIDDEN: Set<String> = setOf("batteryAlert")

        /** The rows of every stored field but the styles and [HIDDEN], in the glasses' order. */
        val ROWS: List<Row> by lazy {
            val out = ArrayList<Row>()
            out += Row("brightness", "Brightness", GLOBAL, "glasses", range = 0..100 step 5)
            out += Row("brightnessAuto", "Brightness · auto", GLOBAL, "glasses", ON_OFF)
            out += Row("heightMode", "Size", GLOBAL, "glasses", ShellSettings.HEIGHTS.map { p(it) to "$it" })
            out += Row("depth", "Depth", GLOBAL, "glasses", ShellSettings.DEPTHS.map { p(it) to "$it" })
            out += Row("presence", "Presence", GLOBAL, "glasses", listOf(p(0) to "away at rest", p(1) to "dim at rest"))
            out += Row("longPress", "Long-press", GLOBAL, "glasses",
                listOf(p(ShellSettings.LongPress.OFF.name) to "off", p(ShellSettings.LongPress.SWITCHER.name) to "switcher"))
            out += Row("taps", "Taps", GLOBAL, "glasses", wm.damage.core.local.Taps.entries.map { p(it.name) to it.word })
            out += Row("notifyDamage", "Notify · Damage", GLOBAL, "shared", ON_OFF)
            out += Row("quietHours", "Quiet hours", GLOBAL, "shared", ShellSettings.QUIET_HOURS.map { p(it) to QuietHours.label(it) })
            out += Row("phoneNotifications", "Phone notifications", GLOBAL, "phone", ON_OFF)
            out += Row("keyboardLayout", "Keyboard", GLOBAL, "glasses", words(ShellSettings.KEYBOARDS))
            // the shell's own rows after the window's (`Shell.globalFontRows`)
            out += Row("fontFace", "Font", GLOBAL, "glasses", words(wm.damage.core.text.Faces.LABELS))
            out += Row("fontScale", "Font size", GLOBAL, "glasses", ShellSettings.SCALES.map { JsonPrimitive(it) to ShellSettings.scaleLabel(it) })
            out += Row("fontStyle", "Font style", GLOBAL, "glasses", words(ShellSettings.STYLES))
            out += Row("silentClock", "Silent clock", GLOBAL, "glasses", words(ShellSettings.SILENT_CLOCKS))
            out += Row("slideFrames", "Slide frames", GLOBAL, "glasses", words(ShellSettings.SLIDE_FRAMES))
            out += Row("slideFill", "Slide fill", GLOBAL, "glasses", words(ShellSettings.SLIDE_FILLS))
            out += Row("cachedText", "Cached text", GLOBAL, "glasses", words(ShellSettings.CACHED_TEXT))
            out += Row("pageStaging", "Page staging", GLOBAL, "glasses", words(ShellSettings.PAGE_STAGING))
            out += Row("linkPriority", "Link", GLOBAL, "phone", words(ShellSettings.LINK_PRIORITIES))
            // Animations, by tier in `MOTION.md` §7's order
            out += Row("scrollEffect", "Scroll effect", anim("Scroll"), "glasses", ScrollEffect.entries.map { p(it.id) to it.word })
            out += Row("scrollLength", "Scroll length", anim("Scroll"), "glasses", ShellSettings.SCROLL_LENGTHS.map { p(it) to "$it ms" })
            out += Row("frames", "Scroll frames", anim("Scroll"), "glasses", frameWords())
            for (t in Transitions.Tier.entries) {
                val w = t.name.lowercase()
                out += Row("${w}Length", "${t.title} length", anim(t.title), "glasses", ShellSettings.SCROLL_LENGTHS.map { p(it) to "$it ms" })
                out += Row("${w}Frames", "${t.title} frames", anim(t.title), "glasses", frameWords())
                out += Row("${w}Effects", t.title, anim(t.title), "glasses", Transitions.effectsOf(t).map { p(it.id) to it.label }, set = true)
            }
            out += Row("popupLength", "Popup length", anim("Popups"), "glasses", ShellSettings.SCROLL_LENGTHS.map { p(it) to "$it ms" })
            out += Row("popupFrames", "Popup frames", anim("Popups"), "glasses", frameWords())
            out += Row("popupUnfurl", "Popup · Unfurl", anim("Popups"), "glasses", ON_OFF)
            out += Row("popupFade", "Popup · Fade", anim("Popups"), "glasses", ON_OFF)
            out += Row("popupSlide", "Popup · Slide", anim("Popups"), "glasses", ON_OFF)
            // the bus watcher's two shared rows (`bastion/BastionRows.kt`)
            out += Row("notifyAlerts", "Notify · alerts", "Bastion", "shared", ON_OFF)
            out += Row("notifyCleared", "Notify · cleared", "Bastion", "shared", ON_OFF)
            out
        }

        private fun frameWords() = ShellSettings.FRAME_COUNTS.map { p(it) to if (it == 0) "auto" else "$it" }

        /** App [id]'s style rows (`Shell.styleRowsFor`), in its own category [name]: the glasses show `Scroll effect` at
         *  ladder stage 5 only; the description carries it whatever the stage. */
        fun styleRows(id: String, name: String): List<Row> {
            val item = APP + id
            return listOf(
                Row("$item.face", "Font", name, "glasses", words(listOf("default") + wm.damage.core.text.Faces.LABELS), item = item, field = "face"),
                Row("$item.scale", "Font size", name, "glasses",
                    listOf(JsonPrimitive(0.0) to "default") + ShellSettings.SCALES.map { JsonPrimitive(it) to ShellSettings.scaleLabel(it) },
                    item = item, field = "scale"),
                Row("$item.style", "Font style", name, "glasses", words(ShellSettings.STYLES), item = item, field = "style"),
                Row("$item.depth", "Depth", name, "glasses",
                    listOf(p(ShellSettings.GLOBAL_DEPTH) to "global") + ShellSettings.DEPTHS.map { p(it) to "$it" }, item = item, field = "depth"),
                Row("$item.scrollEffect", "Scroll effect", name, "glasses",
                    listOf(p("default") to "default") + ScrollEffects.WINDOW_EFFECTS.map { p(it.id) to it.word }, item = item, field = "scrollEffect"),
            )
        }

        /** The description (C§3.1): every row, by category, with its value in [state] (absent = the default). */
        fun describe(state: WindowState, apps: List<Pair<String, String>> = APPS): JsonObject {
            val rows = ROWS + apps.flatMap { (id, name) -> styleRows(id, name) }
            return buildJsonObject {
                put("id", WINDOW)
                put("name", "Settings")
                put("min", Levels.VIEW)
                put("availability", "live")
                putJsonArray("categories") {
                    for ((cat, rs) in rows.groupBy { it.category }) add(buildJsonObject {
                        put("name", cat)
                        put("rows", JsonArray(rs.map { r -> rowJson(r, state) }))
                    })
                }
                putJsonArray("actions") {
                    add(buildJsonObject { put("id", A_SET); put("kind", "register"); put("level", Levels.CONTROL); put("targets", "a row's item") })
                }
            }
        }

        private fun rowJson(r: Row, state: WindowState): JsonObject = buildJsonObject {
            put("id", r.id)
            put("window", if (r.field != null) r.item.removePrefix(APP) else WINDOW)
            put("label", r.label)
            put("scope", r.scope)
            put("type", r.type)
            put("item", r.item)
            r.field?.let { put("field", it) }
            r.range?.let { put("from", it.first); put("to", it.last); put("step", it.step) }
            if (r.options.isNotEmpty()) put("options", buildJsonArray {
                for ((v, l) in r.options) add(buildJsonObject { put("id", v); put("label", l) })
            })
            val v = valueIn(state, r.item)
            val d = defaultOf(r.item)
            put("value", if (r.field != null) (v as? JsonObject)?.get(r.field) ?: (d as JsonObject).getValue(r.field) else v ?: JsonNull)
            put("default", if (r.field != null) (d as JsonObject).getValue(r.field) else d ?: JsonNull)
            put("level", Levels.CONTROL)
        }
    }
}
