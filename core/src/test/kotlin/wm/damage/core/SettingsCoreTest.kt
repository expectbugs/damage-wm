package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wm.damage.core.app.AppClient
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Draft
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.MemoryRecord
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.app.RecordBackend
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowState
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.motion.Transitions
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.SettingsCore
import wm.damage.core.shell.SettingsMigration
import wm.damage.core.shell.SettingsWindow
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage S (the lean spec's §5, Settings): Settings on the PC record — every stored field described once and
 * the glasses' rows carrying its words and options; fold and diff round-tripping every field and a style; a fast adjust
 * never stepped back by the record's late answers (the echo guard: pinned, watched to fail without it); an adjust on a
 * phone over its working copy reaching the PC's record and a page's set reaching the phone's shell once with nothing
 * sent back; the first start before the copy has heard Settings showing the cached values, then the record's; the
 * migration from a 0.84 PC store once, and the PC's Quiet hours following the record.
 */
class SettingsCoreTest {

    /** A backend over a record in memory: it hears Settings only when told ([hear]) and, [holding], keeps the ops it is
     *  given until [release] (the PC's own record answering after a fast adjust). */
    private class Backend(val inner: MemoryRecord = MemoryRecord(SettingsCore.ACTIONS), @Volatile var on: Boolean = true,
                          @Volatile var holding: Boolean = false) : RecordBackend by inner {
        val drafts = CopyOnWriteArrayList<Draft>()
        private val held = ArrayList<Draft>()                 // on the executor
        private val listeners = CopyOnWriteArrayList<SourceListener>()
        override fun heard(window: String) = on
        override fun watch(window: String, l: SourceListener) { listeners += l; inner.watch(window, l) }
        override fun unwatch(window: String, l: SourceListener) { listeners -= l; inner.unwatch(window, l) }
        override fun submit(window: String, draft: Draft) { drafts += draft; if (holding) held += draft else inner.submit(window, draft) }

        /** The first [n] held ops reach the record, in order, each answered as the PC answers (its change told). */
        fun release(n: Int) = executor.call { repeat(minOf(n, held.size)) { inner.submit(SettingsCore.WINDOW, held.removeAt(0)) } }

        /** The first snapshot that shares Settings: heard, and its state told (as the working copy's refresh does). */
        fun hear() = executor.execute("hear") {
            on = true
            for (l in listeners) l.changed(inner.stateOf(SettingsCore.WINDOW), emptySet())
        }

        /** An item the record holds before the shell starts (the migration's, or another surface's), at input time [at]. */
        fun write(name: String, v: JsonElement, at: Long = 1L, action: String = SettingsCore.MIGRATE) =
            executor.call { inner.submit(SettingsCore.WINDOW, Draft(action, listOf(name), v, at = at)) }
    }

    private class Rig(tmp: Path, backend: RecordBackend?, stored: ShellSettings? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val path: Path = Files.createDirectories(tmp).resolve("state.json").also { p ->
            stored?.let { s -> Persistence(p).apply { put(SettingsCore.STORE_KEY, s.toJson()); check(save()) } }
        }
        val store = Persistence(path)
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            store, null, scope)
        val source = backend?.let { LocalWindowSource(SettingsCore(), it) }

        init {
            shell.settingsSource = source
        }

        suspend fun start() {
            shell.start()
            settle()
        }

        suspend fun settle() = waitUntil(20_000, 10, { "the shell did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }

        suspend fun await(what: String, cond: () -> Boolean) = waitUntil(30_000, 10, { "did not converge: $what (${shell.settings})" }, cond)

        suspend fun stop() {
            shell.stop()
            shell.settingsSource = null
            source?.close()
            scope.cancel()
        }
    }

    private fun item(host: AppHost, name: String) = host.read(SettingsCore.WINDOW).third.items[Record.key(SettingsCore.WINDOW, name)]

    @Test
    fun everyStoredFieldHasOneRow_andTheGlassesRowsCarryItsWordsAndOptions(): Unit = runBlocking {
        // the description against the stored fields: one row each, the hidden ones aside (a field added without a row fails)
        val ids = SettingsCore.ROWS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "a row is described twice: $ids")
        assertEquals(SettingsCore.FIELDS.toSet(), ids.toSet() + SettingsCore.HIDDEN, "every stored field has exactly one row")
        for (r in SettingsCore.ROWS) assertTrue(r.options.isNotEmpty() || r.range != null, "${r.id} offers its values")
        for (r in SettingsCore.ROWS) if (r.range == null) assertTrue(r.options.any { it.first == SettingsCore.defaultOf(r.id) } || r.type == "set",
            "${r.id}'s default is one of its options")
        fun labels(cat: String) = SettingsCore.ROWS.filter { it.category == cat }

        val tmp = Files.createTempDirectory("damage-settings-rows")
        val r = Rig(tmp, null)
        r.shell.register(wm.damage.core.windows.games.GamesWindow(FakeText(), r.scope))
        try {
            r.start()
            // Global: the window's own rows (a bit-10 build: `Taps` shown), then the shell's own; Brightness is one ladder
            // on the glasses over two stored fields, `Local input` stores nothing
            val shellRows = r.shell.shellRowsForTest()
            val w = SettingsWindow(FakeText(), { ShellSettings() }, {}, { shellRows }, bindBuild = { true }, effectsInForce = { true })
            assertEquals(labels("Global").map { it.label } - "Brightness · auto",
                w.globalRowNamesForTest() - "Local input", "Global's rows are the description's, in its order")
            for (h in shellRows) {
                val d = labels("Global").single { it.label == h.name }
                assertEquals(d.options.map { it.second }, h.options(), "${h.name}'s options are the description's")
            }
            // Animations: per tier, its rows; a tier's effects are one stored set, one checkbox row per effect on the glasses
            for (t in listOf("Scroll") + Transitions.Tier.entries.map { it.title } + "Popups") {
                val want = labels("Animations · $t").flatMap { d -> if (d.type == "set") d.options.map { "${d.label} · ${it.second}" } else listOf(d.label) }
                assertEquals(want, w.rowsForTest("Animations", t).map { it.first }, "Animations · $t")
            }
            // C3 DW-1b-2: the main page — a row per tier, then its own rows (`Switcher`: the switcher is no tier), at stage 5
            assertEquals(listOf("Scroll") + Transitions.Tier.entries.map { it.title } + "Popups" + labels("Animations").map { it.label },
                w.rowsForTest("Animations").map { it.first }, "the main Animations page")
            assertEquals("Switcher" to "Drum (default)", w.rowsForTest("Animations").last(), "the Switcher row at its default")
            // an app's style rows (`Scroll effect` is shown at ladder stage 5 only, so it is not on this shell's page)
            val games = r.shell.settingsApps().single { it.first == "Games" }.second
            val styles = SettingsCore.styleRows("games", "Games")
            val shown = games.filter { h -> styles.any { it.label == h.name } }
            assertEquals(styles.map { it.label } - "Scroll effect", shown.map { it.name }, "Games' style rows")
            for (h in shown) assertEquals(styles.single { it.label == h.name }.options.map { it.second }, h.options(), "${h.name}'s options")
            // the description itself: every row with its value, at its default here
            val d = SettingsCore().describe(WindowState.empty(SettingsCore.WINDOW))
            val rows = d["categories"]!!.jsonArray.flatMap { it.jsonObject["rows"]!!.jsonArray }
            assertEquals(SettingsCore.ROWS.size + SettingsCore.APPS.size * 5, rows.size)
            for (row in rows) assertEquals(row.jsonObject["default"], row.jsonObject["value"], "${row.jsonObject["id"]} reads its default")
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun foldAndDiffRoundTripEveryFieldAndAStyle() {
        // one value off its default for every stored field, the hidden one included
        val off = LinkedHashMap<String, JsonElement>()
        for (r in SettingsCore.ROWS) off[r.id] = when {
            r.range != null -> JsonPrimitive(35)
            r.type == "set" -> JsonArray(r.options.map { it.first })
            else -> r.options.first { it.first != SettingsCore.defaultOf(r.id) }.first
        }
        off["batteryAlert"] = JsonPrimitive("ESCALATING")
        val style = ShellSettings.AppStyle(face = "Alegreya", scale = 1.3, style = "bold", depth = 12, scrollEffect = "slide")
        off[SettingsCore.APP + "reader"] = SettingsCore.style(style)
        val s = SettingsCore.with(ShellSettings(), off)
        val enc = SettingsCore.encode(s)
        for ((name, v) in off) assertEquals(v, enc[name], "$name round-trips through the codec")
        assertEquals(style, s.appStyle("reader"))
        // the diff from the defaults names exactly those items; folding its ops over the defaults gives the same settings
        val drafts = SettingsCore.diff(ShellSettings(), s, at = 5L)
        assertEquals(off.keys, drafts.map { it.targets.single() }.toSet())
        assertTrue(drafts.all { it.action == SettingsCore.A_SET && it.at == 5L })
        val items = drafts.associate { Record.key(SettingsCore.WINDOW, it.targets.single()) to wm.damage.core.app.Item(it.result, "phone", 5L) }
        assertEquals(s, SettingsCore.fold(items))
        assertEquals(emptyList(), SettingsCore.diff(s, s), "nothing changed: no op")
        // a style set back to its defaults is one op carrying the default style
        val back = SettingsCore.diff(s, s.withAppStyle("reader") { ShellSettings.AppStyle() }).filter { it.targets.single().startsWith(SettingsCore.APP) }
        assertEquals(listOf(SettingsCore.style(ShellSettings.AppStyle())), back.map { it.result })
        // an item that is not a value of its field is left out alone; a field this build no longer stores is ignored
        val bad = SettingsCore.with(ShellSettings(), mapOf("depth" to JsonPrimitive("deep"), "fontScale" to JsonPrimitive(1.3),
            "profiler" to JsonPrimitive(true), SettingsCore.APP + "tmux" to JsonPrimitive(3)))
        assertEquals(ShellSettings(fontScale = 1.3), bad)
    }

    /**
     * The echo guard (the decisions' "Stage G accepted"): on the PC's own record the record answers after the shell has
     * shown later notches; the answer to the first notch must not step the value back. Watched to fail with the guard
     * taken out (`settingsEcho` answering false): the first answer stepped Depth back to 12.
     */
    @Test
    fun aFastAdjustIsNeverSteppedBack_byTheRecordsLateAnswers(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-settings-echo")
        val b = Backend(holding = true)
        val r = Rig(tmp, b)
        try {
            r.start()
            for (d in listOf(12, 16, 4)) r.shell.updateSettings { it.copy(depth = d) }       // three notches, ahead of every answer
            r.settle()
            r.await("three ops sent") { b.drafts.size == 3 }
            assertEquals(listOf(12, 16, 4), b.drafts.map { it.result.jsonPrimitive.int }, "one op per notch, field first")
            assertEquals(listOf("depth"), b.drafts.map { it.targets.single() }.distinct())
            assertEquals(4, r.shell.settings.depth)
            b.release(1)                                   // the first notch's answer arrives after the third is shown
            r.settle()
            assertEquals(4, r.shell.settings.depth, "the first notch's answer did not step Depth back")
            assertEquals(0, r.shell.settingsFromRecordCount)
            b.release(2)
            r.settle()
            assertEquals(4, r.shell.settings.depth)
            assertEquals(0, r.shell.settingsFromRecordCount, "an echo of the shell's own write is never the record moving")
            // a change made elsewhere is still applied, once
            b.write("depth", JsonPrimitive(16), at = System.currentTimeMillis() + 1_000, action = SettingsCore.A_SET)
            r.await("the record's 16") { r.shell.settings.depth == 16 }
            assertEquals(1, r.shell.settingsFromRecordCount)
            assertEquals(3, b.drafts.size, "nothing sent back")
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun thePhonesAdjustReachesThePcRecord_andAPagesSetReachesThePhoneOnce(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-settings-phone")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pc = AppHost.inScratchDir(Files.createDirectories(tmp.resolve("pc")))
        val net = AppNet(pc)
        val server = ContentHostServer(LocalContent(Files.createDirectories(tmp.resolve("books"))), 0, "tok", app = net,
            bindAddress = InetAddress.getLoopbackAddress())
        server.start()
        val ex = CoreExecutor("damage-core-phone-settings")
        val wc = WorkingCopy(tmp.resolve("workcopy"), AppHost.WINDOWS, ex)
        wc.start { stream -> AppClient("127.0.0.1", server.boundPort, AppClient.Credential.Token("tok"), stream, wc, scope) }
        val r = Rig(tmp.resolve("phone"), wc)
        try {
            r.start()
            r.await("the copy has heard Settings") { r.source!!.heard() }
            r.shell.updateSettings { it.copy(depth = 12) }
            r.await("the phone's Depth in the PC record") { item(pc, "depth")?.value == JsonPrimitive(12) }
            assertEquals(wc.device, item(pc, "depth")!!.by)
            // a page's set (another device) reaches the phone's shell, which applies it once and sends nothing back
            r.await("the phone's queue drained") { wc.queue.waiting == 0 }
            pc.act(Op("web", OpId.Queued("page", 1), SettingsCore.WINDOW, SettingsCore.A_SET, listOf("quietHours"),
                JsonPrimitive("22-07"), at = System.currentTimeMillis())) { }
            r.await("the page's Quiet hours on the phone") { r.shell.settings.quietHours == "22-07" }
            val seq = pc.seq
            delay(500)
            assertEquals(seq, pc.seq, "the phone sent nothing back")
            assertEquals("web", item(pc, "quietHours")!!.by)
            assertEquals(1, r.shell.settingsFromRecordCount, "applied once")
            assertEquals(12, r.shell.settings.depth, "its own adjust stands")
            assertEquals("22-07", SettingsCore.onHost(pc).quietHours, "the PC's Quiet hours follow the record")
        } finally {
            r.stop()
            wc.close()
            ex.shutdown()
            server.close()
            net.close()
            pc.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theFirstStartBeforeTheCopyHasHeardSettings_showsTheCachedValuesThenTheRecords(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-settings-heard")
        val b = Backend(on = false)
        b.write("depth", JsonPrimitive(4))                     // his migrated value on the PC
        val r = Rig(tmp, b, stored = ShellSettings(depth = 12, fontScale = 1.15))    // this device's cached copy
        try {
            r.start()
            assertEquals(12, r.shell.settings.depth, "the cached copy shows, never the defaults")
            assertEquals(1.15, r.shell.settings.fontScale)
            delay(300)
            assertEquals(12, r.shell.settings.depth)
            b.hear()
            r.await("the record's values") { r.shell.settings.depth == 4 }
            assertEquals(1.0, r.shell.settings.fontScale, "a field the record does not hold is its default")
            assertEquals(1, r.shell.settingsFromRecordCount)
            assertEquals(4, ShellSettings.fromJson(r.store.get(SettingsCore.STORE_KEY)).depth, "the local copy written")
            assertEquals(emptyList(), b.drafts, "nothing sent: no input")
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theMigrationCarriesThePcStoresSettings_once_andThePcsQuietHoursFollowTheRecord() {
        val tmp = Files.createTempDirectory("damage-settings-migrate")
        try {
            // a 0.84 PC store's synced copy: its JSON omits a field at its default
            val store = Persistence(tmp.resolve("state.json"))
            store.put(SettingsCore.STORE_KEY, buildJsonObject {
                put("depth", 12); put("fontScale", 1.3); put("quietHours", "23-07"); put("batteryAlert", "OFF")
                put("profiler", true)                          // removed (his word): not carried
                put("heightMode", "tall")                      // not a value of the field: not carried
                putJsonObject("appStyles") { putJsonObject("reader") { put("face", "Alegreya") }; put("tmux", 7) }
            })
            val host = AppHost.inScratchDir(Files.createDirectories(tmp.resolve("pc")))
            try {
                val notes = ArrayList<String>()
                SettingsMigration.onHost(host, store) { notes += it }
                assertEquals(JsonPrimitive(12), item(host, "depth")?.value)
                assertEquals(JsonPrimitive(1.3), item(host, "fontScale")?.value)
                assertEquals(JsonPrimitive("OFF"), item(host, "batteryAlert")?.value, "the hidden field is carried")
                assertEquals(SettingsCore.style(ShellSettings.AppStyle(face = "Alegreya")), item(host, SettingsCore.APP + "reader")?.value)
                assertNull(item(host, "profiler"))
                assertNull(item(host, "heightMode"))
                assertNull(item(host, SettingsCore.APP + "tmux"))
                val all = host.read(SettingsCore.WINDOW).third.items.values
                assertEquals(5, all.size)
                assertTrue(all.all { it.by == "migration" })
                assertEquals(1, notes.size)
                val s = SettingsCore.onHost(host)
                assertEquals("23-07", s.quietHours, "the PC's Quiet hours read the record")
                assertEquals(ShellSettings(depth = 12, fontScale = 1.3, quietHours = "23-07", batteryAlert = ShellSettings.BatteryAlert.OFF,
                    appStyles = mapOf("reader" to ShellSettings.AppStyle(face = "Alegreya"))), s, "the record is the store's copy")
                // a second start writes nothing
                val seq = host.seq
                SettingsMigration.onHost(host, store) { notes += it }
                assertEquals(seq, host.seq)
                assertEquals(1, notes.size)
                // a later set (a page, the phone) is what the PC's notices read
                val done = java.util.concurrent.CountDownLatch(1)
                host.act(Op("phone", OpId.Queued("p", 1), SettingsCore.WINDOW, SettingsCore.A_SET, listOf("quietHours"),
                    JsonPrimitive("off"), at = System.currentTimeMillis())) { done.countDown() }
                assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "the set was answered")
                assertEquals("off", SettingsCore.onHost(host).quietHours)
            } finally {
                host.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Every start path on the PC (spec §4.2's migration; the brief's start paths), each on a record over a directory
     * closed and opened again as a process end and start leave it: a start with no file writes nothing and reads the
     * defaults; the first start after the update migrates the store's copy and a second start writes nothing; a restore
     * of a copy taken after the migration writes nothing; a restore of a record from before the update (no Settings
     * items) beside today's store migrates again.
     */
    @Test
    fun everyStartPath_noFile_theFirstAfterTheUpdate_aSecond_andARestore() {
        val tmp = Files.createTempDirectory("damage-settings-starts")
        fun open(dir: Path) = AppHost(Files.createDirectories(dir), AppHost.WINDOWS, CoreExecutor("damage-core-settings-start")).also { it.open() }
        fun end(h: AppHost) { h.close(); h.executor.shutdown() }
        fun store(dir: Path) = Persistence(Files.createDirectories(dir).resolve("state.json")).also { it.load() }
        try {
            // no file: no store, a new record
            open(tmp.resolve("none/pc")).let { h ->
                SettingsMigration.onHost(h, store(tmp.resolve("none"))) { }
                assertEquals(0L, h.seq, "nothing written")
                assertEquals(ShellSettings(), SettingsCore.onHost(h))
                end(h)
            }
            // the first start after the update: the 0.84 store's synced copy, the record without Settings
            val data = tmp.resolve("data")
            store(data).apply { put(SettingsCore.STORE_KEY, ShellSettings(depth = 4, quietHours = "21-06").toJson()); check(save()) }
            var h = open(data.resolve("pc"))
            SettingsMigration.onHost(h, store(data)) { }
            val seq = h.seq
            assertEquals(2L, seq, "two items written")
            end(h)
            // the second start on the same directory: the items replayed, nothing written
            h = open(data.resolve("pc"))
            SettingsMigration.onHost(h, store(data)) { }
            assertEquals(seq, h.seq)
            assertEquals(ShellSettings(depth = 4, quietHours = "21-06"), SettingsCore.onHost(h))
            end(h)
            // a restore of a copy taken after the migration: nothing written
            val copy = tmp.resolve("restored")
            data.toFile().copyRecursively(copy.toFile())
            h = open(copy.resolve("pc"))
            SettingsMigration.onHost(h, store(copy)) { }
            assertEquals(seq, h.seq)
            assertEquals(4, SettingsCore.onHost(h).depth)
            end(h)
            // a restore of a record from before the update beside today's store: the store's copy written again
            val old = tmp.resolve("old")
            store(old).apply { put(SettingsCore.STORE_KEY, ShellSettings(depth = 16).toJson()); check(save()) }
            open(old.resolve("pc")).let { o -> end(o) }                      // a record that never held Settings
            h = open(old.resolve("pc"))
            SettingsMigration.onHost(h, store(old)) { }
            assertEquals(16, SettingsCore.onHost(h).depth)
            end(h)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
