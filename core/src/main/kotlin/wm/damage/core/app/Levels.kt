package wm.damage.core.app

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §4.2, §10.4; the spec's §6 "Levels"): a device's level, and the level each
 * window and action needs. `view` reads, with the reading effects (open, the place, `recent`); `control` adds the
 * reversible and irreversible actions inside Damage (a bookmark, reset progress, the glasses rows); `full` adds the
 * device table and anything that reaches outside. The PC checks every op, subscription and blob against the device's
 * level at apply time; a presenter's dim is a courtesy, never the check.
 */
object Levels {
    const val VIEW = "view"
    const val CONTROL = "control"
    const val FULL = "full"

    /** Lowest first. */
    val ALL: List<String> = listOf(VIEW, CONTROL, FULL)

    fun valid(level: String): Boolean = level in ALL

    /** True when [have] reaches [need]; an unknown level reaches nothing and is reached by nothing. */
    fun atLeast(have: String, need: String): Boolean {
        val h = ALL.indexOf(have)
        val n = ALL.indexOf(need)
        return h >= 0 && n >= 0 && h >= n
    }

    /** The refusal a device at [have] gets for something that needs [need], or null when it reaches it. */
    fun refusal(have: String, need: String, what: String): Answer.Refused? =
        if (atLeast(have, need)) null
        else Answer.Refused("$what needs the level $need; this device is approved at $have", Answer.LEVEL)
}

/** The level an action needs; null = this build has no such action (it then needs [Levels.FULL]). */
fun interface ActionLevels {
    fun need(window: String, action: String): String?
}
