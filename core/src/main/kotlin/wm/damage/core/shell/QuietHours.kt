package wm.damage.core.shell

import java.time.LocalTime

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own
 * network. Quiet hours (build W, the lead's decision B.2; `CONTRACT.md` §7.3, §14 (b)): the ONE shared
 * notice-policy row, in Global beside `Notify · Damage` ([ShellSettings.quietHours], synced as `shell.settings`).
 *
 * In this build it governs the bus alert watcher's notices and "home unreachable" only (`bastion/AlertDelivery.kt`):
 * inside the window a NORMAL raise, repeat or clear is not delivered, and a raise still up at the end is delivered
 * once; URGENT passes. Step S1-2's notice record applies the same row to every notice — nothing about the row, its
 * ids or [inside] moves then.
 *
 * The hours are the device's local wall clock, read at each decision by the caller: pacing on a clock, never a
 * deadline (no timeout is stored anywhere).
 */
object QuietHours {

    const val OFF = "off"

    /** (stored id, the row's label), in the row's order. A window runs from its start hour (inclusive) to its end
     *  hour (exclusive) and may cross midnight. */
    val PRESETS: List<Pair<String, String>> = listOf(
        OFF to "off",
        "21-06" to "21–06",
        "22-07" to "22–07",
        "23-07" to "23–07",
        "00-08" to "00–08",
    )

    /** The stored ids, in the row's order ([ShellSettings.QUIET_HOURS]). */
    val IDS: List<String> = PRESETS.map { it.first }

    /** What the Global row prints for [id]; an unknown id prints as itself (the settings clamp makes it `off`). */
    fun label(id: String): String = PRESETS.firstOrNull { it.first == id }?.second ?: id

    /** (start hour, end hour) of [id]; null for `off` or an id that is not a preset. */
    fun hours(id: String): Pair<Int, Int>? {
        if (id == OFF || id !in IDS) return null
        val (a, b) = id.split('-')
        return a.toInt() to b.toInt()
    }

    /** Is [t] inside [preset]'s window? Start inclusive, end exclusive; a window whose start is after its end
     *  crosses midnight. `off` (or an unknown id) is never inside. */
    fun inside(preset: String, t: LocalTime): Boolean {
        val (start, end) = hours(preset) ?: return false
        val s = t.toSecondOfDay()
        val from = start * 3600
        val to = end * 3600
        return if (from < to) s >= from && s < to else s >= from || s < to
    }
}
