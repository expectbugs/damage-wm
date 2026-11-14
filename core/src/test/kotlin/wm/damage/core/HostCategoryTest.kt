package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView

/**
 * Build W, package C (R6 T13, pin W16): a HOST category (`Shell.hostCategories`, the phone's "Bastion") is listed
 * by Settings' per-app supplier exactly once, with its ONE stable row list (Settings matches a staged row by
 * identity); a registered window of the same name wins (case-insensitive) and the host's category is not listed
 * beside it; a shared row applies through the shell's settings; an old settings record reads the new fields'
 * defaults.
 */
class HostCategoryTest {

    private class Win(name: String, private val rows: List<HostSetting>) : DamageWindow("busw", name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.CanvasView({ _, _ -> })
        override fun summary() = Summary("w")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
        override fun appSettings() = rows
    }

    private fun phoneRow(name: String) = HostSetting(name, listOf("on", "off"), { "on" }, { })

    @Test
    fun listedOnceWithOneStableRowList(): Unit = runBlocking {
        ShellRig().use { r ->
            val rows = listOf(
                r.shell.sharedRow("Notify · alerts", listOf("on", "off"), { if (it.notifyAlerts) "on" else "off" },
                    { s, v -> s.copy(notifyAlerts = v == "on") }),
                phoneRow("Phone · alerts"))
            r.shell.hostCategories = listOf("Bastion" to rows)
            r.shell.start(); r.settle("start")
            val first = r.shell.settingsApps()
            assertEquals(1, first.count { it.first == "Bastion" }, "listed once: ${first.map { it.first }}")
            val again = r.shell.settingsApps().single { it.first == "Bastion" }.second
            assertEquals(rows.size, again.size)
            for (i in rows.indices) assertSame(rows[i], again[i], "the same row objects on every read")
            // a shared row: its apply goes through the shell's settings (on the loop, as Settings calls it)
            assertEquals("on", rows[0].current())
            r.shell.services.runOnShell { rows[0].apply("off") }
            r.settle("the row applied")
            assertFalse(r.shell.settings.notifyAlerts, "the shared field changed")
            assertEquals("off", rows[0].current())
            r.shell.stop()
        }
    }

    @Test
    fun aWindowOfTheSameNameWins(): Unit = runBlocking {
        ShellRig().use { r ->
            val windowRow = phoneRow("Window's own row")
            r.shell.register(Win("bastion", listOf(windowRow)))
            r.shell.hostCategories = listOf("Bastion" to listOf(phoneRow("Phone · alerts")))
            r.shell.start(); r.settle("start")
            val cats = r.shell.settingsApps().filter { it.first.equals("Bastion", ignoreCase = true) }
            assertEquals(1, cats.size, "one category, not two: ${cats.map { it.first }}")
            assertSame(windowRow, cats.single().second.first(), "the window's rows, not the host's")
            r.shell.stop()
        }
    }

    @Test
    fun anOldSettingsRecordReadsTheDefaults(): Unit = runBlocking {
        val old = buildJsonObject { put("notifyDamage", false); put("keyboardLayout", "abc") }
        val s = ShellSettings.fromJson(old)
        assertTrue(s.notifyAlerts && s.notifyCleared, "the new rows default on")
        assertEquals("off", s.quietHours)
        assertFalse(s.notifyDamage, "the old fields still read")
        assertEquals("abc", s.keyboardLayout)
        assertEquals("off", ShellSettings.fromJson(buildJsonObject { put("quietHours", "21-09") }).quietHours,
            "an id that is not a preset clamps to off")
        val back = ShellSettings.fromJson(ShellSettings(notifyCleared = false, quietHours = "23-07").toJson())
        assertEquals(false, back.notifyCleared)
        assertEquals("23-07", back.quietHours, "round trip")
    }
}
