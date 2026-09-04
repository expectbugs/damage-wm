package wm.damage.core.windows.games.kit

/**
 * A list of legal actions → a shell level, with the confirm policy and the
 * §1.7 rest positions applied (`HOLDEM.md` §6, §10.2–§10.4). Nothing here
 * knows what Hold'em is: it takes actions and answers "does this one stage a
 * confirm, and what does the confirm say".
 *
 * 🔴 **Verdict 33: Check and Fold are the SAME top row** — contextual, Check
 * when checking is free and Fold when facing a bet — and it is **not exempt
 * from the confirm**, precisely because a fixed muscle memory over a row that
 * changes meaning is how a reflex fold happens. (Exempting Check was proposed
 * and rejected by Adam. Do not re-propose.)
 *
 * 🔴 **Verdict 32: the cursor rests on Cancel.** One notch, one tap. The first
 * tap is therefore always harmless, which is the useful consequence noted in
 * §10.4: §1.7 no longer forces the action list into an unnatural order, so the
 * rows can sit in natural poker order.
 */
object ActionLevel {

    enum class Kind {
        /** The contextual give-up row: Check when free, Fold when facing a bet. */
        CHECK, FOLD,
        CALL, BET, RAISE, ALL_IN,
        /** Not a betting action — a window row that happens to share the level. */
        NAV,
    }

    data class Action(
        val kind: Kind,
        val label: String,
        val detail: String = "",
        /** Chips this action puts in, where one is defined. */
        val amount: Int = 0,
    ) {
        /** Does it move money? Check and Fold do not. */
        val money: Boolean get() = kind == Kind.CALL || kind == Kind.BET ||
            kind == Kind.RAISE || kind == Kind.ALL_IN
    }

    /** Settings → Games → `Confirm` (§10.4). */
    enum class Confirm(val label: String) {
        ALL("All actions"),
        /** Only actions that put chips in — Check and Fold commit at once. */
        MONEY("Money only"),
        /** Only shoving. The fastest, and the least protected. */
        ALL_IN("All-in only");

        companion object {
            val LABELS = entries.map { it.label }
            fun byLabel(s: String): Confirm = entries.firstOrNull { it.label == s } ?: ALL
        }
    }

    fun needsConfirm(policy: Confirm, a: Action): Boolean = when (policy) {
        Confirm.ALL -> a.kind != Kind.NAV
        Confirm.MONEY -> a.money
        Confirm.ALL_IN -> a.kind == Kind.ALL_IN
    }

    /** The confirm row's own wording — the exact amount belongs HERE, which is
     *  why the confirm sits after sizing rather than before it (§10.4). */
    fun confirmLabel(a: Action): String = when (a.kind) {
        Kind.CHECK -> "Confirm · Check"
        Kind.FOLD -> "Confirm · Fold"
        Kind.CALL -> "Confirm · Call ${Money.fmt(a.amount)}"
        Kind.BET -> "Confirm · Bet ${Money.fmt(a.amount)}"
        Kind.RAISE -> "Confirm · Raise to ${Money.fmt(a.amount)}"
        Kind.ALL_IN -> "Confirm · All-in ${Money.fmt(a.amount)}"
        Kind.NAV -> "Confirm · ${a.label}"
    }

    /**
     * The preset sizing ladder (§10.3): `Min` · `1/3 pot` · `1/2 pot` ·
     * `3/4 pot` · `Pot` · `All-in`, deduplicated and clamped into
     * \[[minRaiseTo], [maxTo]\]. Amounts are TOTALS to raise TO, which is what
     * no-limit rules and the confirm both speak in.
     *
     * [pot] is the pot AFTER the caller would call — the standard "pot-sized
     * raise" definition: pot + toCall + toCall.
     */
    fun sizingLadder(pot: Int, toCall: Int, committed: Int, minRaiseTo: Int, maxTo: Int,
        verb: Kind = if (toCall > 0) Kind.RAISE else Kind.BET): List<Action> {
        val potAfterCall = pot + toCall
        val out = LinkedHashMap<Int, Action>()
        fun add(kind: Kind, label: String, to: Int) {
            val v = to.coerceIn(minRaiseTo, maxTo)
            if (v <= committed) return
            out.putIfAbsent(v, Action(if (v >= maxTo) Kind.ALL_IN else kind, label, Money.fmt(v), v))
        }
        add(verb, if (verb == Kind.RAISE) "Min" else "Min bet", minRaiseTo)
        add(verb, "1/3 pot", committed + toCall + potAfterCall / 3)
        add(verb, "1/2 pot", committed + toCall + potAfterCall / 2)
        add(verb, "3/4 pot", committed + toCall + potAfterCall * 3 / 4)
        add(verb, "Pot", committed + toCall + potAfterCall)
        out.remove(maxTo)
        // ASCII fractions on purpose: U+2153 (⅓) is outside Latin-1 and no
        // locked face is guaranteed to carry it — an absent glyph is silent
        // tofu on the glass (WINDOWS.md §5). The design record writes ⅓/½/¾.
        val list = ArrayList(out.values)
        list.add(Action(Kind.ALL_IN, "All-in", Money.fmt(maxTo), maxTo))
        return list
    }
}
