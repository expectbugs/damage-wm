package wm.damage.core

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import wm.damage.core.shell.QuietHours
import wm.damage.core.shell.ShellSettings

/**
 * Build W, package C (the spec's new T20): Global `Quiet hours` — every preset at the edges that matter, start
 * inclusive, end exclusive, crossing midnight; `off` never inside; the stored ids, the labels and the clamp.
 */
class QuietHoursTest {

    private fun t(s: String): LocalTime = LocalTime.parse(s)

    @Test
    fun everyPresetAtItsEdges() {
        val times = listOf("20:59", "21:00", "21:59:59", "22:00", "23:00", "23:59", "00:00", "05:59", "05:59:59",
            "06:00", "06:59", "07:00", "07:59", "08:00", "12:00")
        val want = mapOf(
            "off" to emptySet(),
            "21-06" to setOf("21:00", "21:59:59", "22:00", "23:00", "23:59", "00:00", "05:59", "05:59:59"),
            "22-07" to setOf("22:00", "23:00", "23:59", "00:00", "05:59", "05:59:59", "06:00", "06:59"),
            "23-07" to setOf("23:00", "23:59", "00:00", "05:59", "05:59:59", "06:00", "06:59"),
            "00-08" to setOf("00:00", "05:59", "05:59:59", "06:00", "06:59", "07:00", "07:59"),
        )
        assertEquals(want.keys.toList(), QuietHours.IDS, "the row's order and ids")
        for ((id, inside) in want) for (s in times) {
            assertEquals(s in inside, QuietHours.inside(id, t(s)), "$id at $s")
        }
    }

    @Test
    fun labelsIdsAndTheClamp() {
        assertEquals(listOf("off", "21–06", "22–07", "23–07", "00–08"), QuietHours.IDS.map(QuietHours::label))
        assertEquals(QuietHours.IDS, ShellSettings.QUIET_HOURS)
        assertEquals(false, QuietHours.inside("25-03", t("01:00")), "an unknown id is never inside")
        assertEquals("off", ShellSettings(quietHours = "25-03").clamped().quietHours, "an unknown id clamps to off")
        assertEquals("22-07", ShellSettings(quietHours = "22-07").clamped().quietHours)
        assertEquals("off", ShellSettings().quietHours, "default off (dec. B.2)")
    }
}
