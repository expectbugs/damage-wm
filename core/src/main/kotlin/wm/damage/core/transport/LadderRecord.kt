package wm.damage.core.transport

/**
 * Build C2 (`FIRMWARE.md` §6.8, spec DP1 / P12): what the phone keeps on its own storage for the arming ladder and local
 * input, so that a restart of its process (every APK install) loses none of it — the ladder's stage (0..5: 5 is C3's EFFECTS,
 * §7.9; `CfwTransportBase.LADDER_TOP_STAGE`), the time of
 * the last session in which stage 4 was armed, the case-in that matters, the stage-4 hold-back, and (build C2-FA) the last
 * session's cache size. `Taps` is a Settings
 * row (`ShellSettings.taps`, on the PC record with the phone's working copy — stage DP2, decisions N.1), not the
 * transport's.
 * It grows build C1-P's stage file in place: the first line is still the stage's digit, so a file C1-P wrote ("3\n") reads
 * as stage 3 with everything else at its default, and a record with nothing but a stage is written exactly as C1-P wrote
 * it. Every other field is one `key=value` line, written only when it is not its default.
 *
 * Times are the phone's wall clock in ms. [caseInAt] is the first `case` note saying `in case 1` after the later of
 * [bindArmedAt] and [holdBackAt]: the two decisions that read a case-in (a reset after one holds nothing back; one after
 * the hold-back lifts it) need no later one, so the file is written once per arming or hold-back, not at every case note
 * (the contract's "the last case note", read so — `C2-DP1-built.md` §0).
 */
data class LadderRecord(
    val stage: Int = 0,
    val bindArmedAt: Long? = null,
    val caseInAt: Long? = null,
    /** A stage-4 hold-back in force: when it was set; null = none. */
    val holdBackAt: Long? = null,
    /** Its reason as the fault says it ("a lens restarted at 14:02 (RIGHT's uptime)"). */
    val holdBackWhy: String = "",
    /** Build C2-FA (decisions AD.1, AH.1): the last session's cache size, so that a new process's first start asks at
     *  most it (C1-Q's rule with the last session remembered). Before each start's op 5 goes out it becomes
     *  min(itself, the ask) — a lower bound of every lens's cache whenever the process ends — and after the answer the
     *  session's size; written only when it changes. Null, the default and no line in the file, is an older record
     *  (before this field), read as 160 KiB until its first ask; a value outside [SESSION_KIB_RANGE] or one that is not
     *  a number reads the same (said as a line that could not be used). */
    val sessionKib: Int? = null,
) {
    fun encode(): String = buildString {
        append(stage).append('\n')
        bindArmedAt?.let { append("bindArmedAt=").append(it).append('\n') }
        caseInAt?.let { append("caseInAt=").append(it).append('\n') }
        holdBackAt?.let { append("holdBackAt=").append(it).append('\n').append("holdBackWhy=").append(holdBackWhy.replace('\n', ' ')).append('\n') }
        sessionKib?.let { append("sessionKib=").append(it).append('\n') }
    }

    companion object {
        /** The cache sizes op 5 can give a session (`FIRMWARE.md` §4 and §5.9: 64 KiB up to the riders' 256). */
        val SESSION_KIB_RANGE = wm.damage.core.wire.DamageMsg.CACHE_MIN_KIB..wm.damage.core.wire.DamageMsg.CACHE_BUDGET_RIDERS_KIB

        /** What [decode] found: the record, and what to say about lines it could not use (empty: nothing). */
        class Read(val record: LadderRecord, val problems: List<String>, val stageText: String?)

        /** [text] as written by [encode] (or by C1-P). A first line that is not a stage in 0..[top] gives the default
         *  record with [Read.stageText] set — the safe end, said by the caller as C1-P said it; another line that cannot
         *  be used is left at its default and named in [Read.problems]. */
        fun decode(text: String, top: Int): Read {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val first = lines.firstOrNull() ?: ""
            val stage = first.toIntOrNull()
            if (stage == null || stage !in 0..top) return Read(LadderRecord(), emptyList(), first)
            var r = LadderRecord(stage = stage)
            val problems = ArrayList<String>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore('=', "")
                val value = line.substringAfter('=', "")
                val next = when (key) {
                    "bindArmedAt" -> value.toLongOrNull()?.let { r.copy(bindArmedAt = it) }
                    "caseInAt" -> value.toLongOrNull()?.let { r.copy(caseInAt = it) }
                    "holdBackAt" -> value.toLongOrNull()?.let { r.copy(holdBackAt = it) }
                    "holdBackWhy" -> r.copy(holdBackWhy = value)
                    "sessionKib" -> value.toIntOrNull()?.takeIf { it in SESSION_KIB_RANGE }?.let { r.copy(sessionKib = it) }
                    else -> null
                }
                if (next == null) problems += line else r = next
            }
            return Read(r, problems, null)
        }
    }
}
