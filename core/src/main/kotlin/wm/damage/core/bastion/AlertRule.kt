package wm.damage.core.bastion

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. A rule says when a reading of his own home hub raises a notice (build W, R6 §2;
 * the lead's decisions A.1 and B.1).
 *
 * The rules live in their own file, `bastion-rules.json` in the data directory (config key
 * `haRulesFile`), read once at start: `{"rules":[{…},…]}`. Each rule is decoded from its OWN JSON
 * object, so a bad rule refuses only itself (said with its id and why; the watcher raises
 * `watcher.rule.<id>`, and its alert, if up, is held as it is), and the rest run. A file that cannot
 * be used keeps every rule alert up as it is; only a rule absent from a readable file is gone.
 *
 * An edit applies at the next start (no hot reload). A `severity` edit applies from the alert's NEXT
 * episode: an alert already up keeps the severity it was raised with — its repeats and quiet hours —
 * until it is acknowledged or cleared (fix round 1 of build W, the lead's ruling on review 1 note 7).
 *
 * Package E (the lead's decisions F.1–F.3, Adam's words of 2026-11-13: "if grey or fresh tanks change by
 * more than 3% while I'm at work or away from bus"): any rule may carry a guard, `"when": {"entity": e,
 * "equals" | "notEquals": s}` ([Guard]), and the kind `change` compares a numeric reading with a baseline
 * ([RuleKind.CHANGE]). A guard's entity is a hub entity (read like a rule's) or one of Damage's own values
 * ([AlertRule.BUILT_IN_VALUES]: `damage.phone`), which are usable only in a `when`.
 */
enum class Severity(val wire: String) {
    /** Said once, or every `repeatMin` minutes while raised; held inside quiet hours. */
    NORMAL("normal"),
    /** Repeats every ten minutes until acknowledged, whatever `repeatMin` says, and passes quiet hours (dec. B.1). */
    URGENT("urgent");

    companion object {
        fun of(wire: String): Severity? = entries.firstOrNull { it.wire == wire }
    }
}

/** R6 §2 "Kinds"; the semantics are [RuleEval]'s. */
enum class RuleKind(val wire: String) {
    ABOVE("above"), BELOW("below"), EQUALS("equals"), NOT_EQUALS("notEquals"),
    UNAVAILABLE("unavailable"), STALE("stale"),
    /** The numeric reading differs from a baseline by `at` or more (in the entity's own units), held `forMin`
     *  (dec. F.2). Without `withinMin` the baseline is the value at arming, re-taken on acknowledge and kept in
     *  `alerts.json`; with it, the value `withinMin` minutes ago from a ring kept in memory. */
    CHANGE("change");

    companion object {
        fun of(wire: String): RuleKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** The `change` kind's direction (dec. F.2): any (default), up only, down only. */
enum class Direction(val wire: String) {
    ANY("any"), UP("up"), DOWN("down");

    companion object {
        fun of(wire: String): Direction? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A rule's guard (dec. F.1): the rule is judged only while [entity]'s state [equals] [value] (or, `notEquals`,
 * differs from it). The entity unreadable, `unavailable` or `unknown` (and `damage.phone` reading `unknown`) is
 * NO READING: nothing arms, nothing clears. The guard ending resets the rule's clocks and baseline and clears its
 * alert. So [value] is never `unavailable` or `unknown` (the parser refuses them: such a guard could never decide).
 */
data class Guard(val entity: String, val value: String, val equals: Boolean) {
    /** Whether the guard holds for a usable state (the caller has already set no reading aside). */
    fun holds(state: String): Boolean = (state == value) == equals

    /** The guard's key in the rules file. */
    val wire: String get() = if (equals) "equals" else "notEquals"

    /** Plain words for a state line and the guard's clear text: "damage.phone is away" / "… is not home". */
    fun words(): String = "$entity ${if (equals) "is" else "is not"} $value"

    /** A built-in value ([AlertRule.BUILT_IN_VALUES]) rather than a hub entity: not read from the hub. */
    val builtIn: Boolean get() = entity.startsWith(AlertRule.BUILT_IN_DOMAIN)
}

data class AlertRule(
    val id: String,
    val entity: String,
    val kind: RuleKind,
    /** ABOVE/BELOW: the raise threshold. */
    val at: Double? = null,
    /** ABOVE: clears at or below this (< [at]); BELOW: at or above it (> [at]). The band between is hysteresis. */
    val clearAt: Double? = null,
    /** EQUALS/NOT_EQUALS: the state string compared. */
    val value: String? = null,
    /** Minutes the raise condition must hold before the raise; > 0 for UNAVAILABLE and STALE. */
    val forMin: Int = 0,
    /** The notice's words, never cut. */
    val text: String,
    /** Minutes between repeats while raised (0 = said once). Ignored for URGENT, which repeats every ten. */
    val repeatMin: Int = 0,
    val severity: Severity = Severity.NORMAL,
    /** The rule's `when` (dec. F.1); null = always judged. */
    val guard: Guard? = null,
    /** CHANGE: which way counts. */
    val direction: Direction = Direction.ANY,
    /** CHANGE: 0 = compared with the baseline taken at arming; 1..1440 = with the value that many minutes ago. */
    val withinMin: Int = 0,
) {
    val forMs: Long get() = forMin * 60_000L

    /** The hub entities this rule needs read: its own, and its guard's unless that is a built-in value. Every one
     *  of them joins the reader's closed path set ([HaReader]'s `entities`). */
    val hubEntities: List<String> get() = listOfNotNull(entity, guard?.entity?.takeUnless { guard.builtIn })

    /** Milliseconds between repeats while RAISED; 0 = said once. URGENT: fixed at ten minutes (dec. B.1). */
    val repeatMs: Long get() = if (severity == Severity.URGENT) URGENT_REPEAT_MS else repeatMin * 60_000L

    companion object {
        /** An urgent alert repeats every ten minutes until acknowledged (dec. B.1). */
        const val URGENT_REPEAT_MS = 600_000L
        /** The ids of the watcher's own alerts (hub not answering, token refused, a refused rule, …). */
        const val BUILT_IN_PREFIX = "watcher."
        val ID = Regex("^[a-z0-9_.-]+$")

        /** Damage's own values live in the `damage` domain (no hub has one); usable only in a `when`. */
        const val BUILT_IN_DOMAIN = "damage."
        /** Where the phone is, as the PC reads it ([PhonePresence]): `home`, `away` or `unknown` (dec. F.3). */
        const val PHONE = "damage.phone"
        /** Each built-in value and the words a guard may compare it with (`unknown` is no reading, never a match). */
        val BUILT_IN_VALUES: Map<String, Set<String>> = mapOf(PHONE to setOf(PhonePresence.HOME, PhonePresence.AWAY))
        /** A `withinMin` is 1 to this many minutes (one day). */
        const val WITHIN_MAX = 1440
    }
}

/** A rule the file holds but the watcher will not run: its id (or `#<n>`, its 1-based place in the list, when
 *  it has no usable id) and why, in plain words. */
data class RefusedRule(val id: String, val why: String)

class RulesLoad(
    val rules: List<AlertRule>,
    val refused: List<RefusedRule>,
    /** The file as a whole could not be used (not JSON, no `rules` list, unreadable); null otherwise. */
    val fileProblem: String?,
    /** No file at the path: no rules, and that is said at start by the caller. */
    val missing: Boolean = false,
)

object RulesFile {

    private val COMMON = setOf("id", "entity", "kind", "text", "forMin", "repeatMin", "severity", "note", "when")
    private val ABSENT = setOf("unavailable", "unknown")
    private val KEYS = mapOf(
        RuleKind.ABOVE to COMMON + setOf("at", "clearAt"),
        RuleKind.BELOW to COMMON + setOf("at", "clearAt"),
        RuleKind.EQUALS to COMMON + "value",
        RuleKind.NOT_EQUALS to COMMON + "value",
        RuleKind.UNAVAILABLE to COMMON,
        RuleKind.STALE to COMMON,
        RuleKind.CHANGE to COMMON + setOf("at", "direction", "withinMin"),
    )

    /** Reads [path]. A missing file is no rules ([RulesLoad.missing]); an unreadable one is a [RulesLoad.fileProblem]. */
    fun load(path: Path): RulesLoad {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: NoSuchFileException) {
            return RulesLoad(emptyList(), emptyList(), null, missing = true)
        } catch (e: Exception) {
            return RulesLoad(emptyList(), emptyList(), "the rules file could not be read (${e::class.simpleName}: ${e.message})")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return RulesLoad(emptyList(), emptyList(), "the rules file is not UTF-8 text")
        }
        return parse(text)
    }

    fun parse(text: String): RulesLoad {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return RulesLoad(emptyList(), emptyList(), "the rules file is not JSON (${e.message})")
        }
        val list = (root as? JsonObject)?.get("rules") as? JsonArray
            ?: return RulesLoad(emptyList(), emptyList(), "the rules file is not a JSON object with a \"rules\" list")
        val rules = ArrayList<AlertRule>()
        val refused = ArrayList<RefusedRule>()
        val firstAt = HashMap<String, Int>()
        for ((i, el) in list.withIndex()) {
            val place = "#${i + 1}"
            val o = el as? JsonObject
            if (o == null) {
                refused += RefusedRule(place, "not a JSON object")
                continue
            }
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || !AlertRule.ID.matches(id)) {
                refused += RefusedRule(place, "no usable id (lower-case letters, digits, '_', '.' and '-')")
                continue
            }
            if (id.startsWith(AlertRule.BUILT_IN_PREFIX)) {
                refused += RefusedRule(id, "ids starting \"${AlertRule.BUILT_IN_PREFIX}\" are the watcher's own")
                continue
            }
            val first = firstAt[id]
            if (first != null) {
                refused += RefusedRule(id, "the id is already used by rule #$first")
                continue
            }
            firstAt[id] = i + 1
            when (val r = decode(id, o)) {
                is AlertRule -> rules += r
                is String -> refused += RefusedRule(id, r)
            }
        }
        return RulesLoad(rules, refused, null)
    }

    /** One rule from its own object: the rule, or why it is refused (a String). */
    private fun decode(id: String, o: JsonObject): Any {
        val kindWire = text(o, "kind") ?: return "kind missing or not text"
        val kind = RuleKind.of(kindWire)
            ?: return "kind \"$kindWire\" is not one of ${RuleKind.entries.joinToString { it.wire }}"
        val extra = o.keys - KEYS.getValue(kind)
        if (extra.isNotEmpty()) return "${extra.sorted().joinToString()} does not apply to kind ${kind.wire}"
        val entity = text(o, "entity") ?: return "entity missing or not text"
        if (!HaReader.ENTITY.matches(entity)) return "entity \"$entity\" is not a hub entity id (domain.object_id)"
        if (entity.startsWith(AlertRule.BUILT_IN_DOMAIN))
            return "entity \"$entity\" is one of Damage's own values, usable only in a when"
        val words = text(o, "text")
        if (words == null || words.isBlank()) return "text missing: the notice needs words"
        val forMin = minutes(o, "forMin") ?: return "forMin is not a whole number of minutes, 0 or more"
        val repeatMin = minutes(o, "repeatMin") ?: return "repeatMin is not a whole number of minutes, 0 or more"
        val severity = when (val s = o["severity"]) {
            null -> Severity.NORMAL
            else -> (s as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Severity.of(it) }
                ?: return "severity is not \"normal\" or \"urgent\""
        }
        val guard = when (val w = o["when"]) {
            null -> null
            else -> when (val g = guard(w)) {
                is Guard -> g
                else -> return "when: $g"
            }
        }
        var at: Double? = null
        var clearAt: Double? = null
        var value: String? = null
        var direction = Direction.ANY
        var withinMin = 0
        when (kind) {
            RuleKind.ABOVE, RuleKind.BELOW -> {
                at = number(o, "at") ?: return "at missing or not a number"
                clearAt = number(o, "clearAt") ?: return "clearAt missing or not a number"
                if (kind == RuleKind.ABOVE && clearAt >= at) return "above needs clearAt below at"
                if (kind == RuleKind.BELOW && clearAt <= at) return "below needs clearAt above at"
            }
            RuleKind.EQUALS, RuleKind.NOT_EQUALS -> {
                value = (o["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    ?: return "value missing"
            }
            RuleKind.UNAVAILABLE, RuleKind.STALE -> {
                if (forMin <= 0) return "${kind.wire} needs forMin above 0"
            }
            RuleKind.CHANGE -> {
                at = number(o, "at") ?: return "at missing or not a number"
                if (at <= 0.0) return "change needs at above 0 (the size of the change, in the entity's own units)"
                direction = when (val d = o["direction"]) {
                    null -> Direction.ANY
                    else -> (d as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Direction.of(it) }
                        ?: return "direction is not ${Direction.entries.joinToString(", ") { "\"${it.wire}\"" }}"
                }
                if (o["withinMin"] != null) {
                    withinMin = minutes(o, "withinMin")?.takeIf { it in 1..AlertRule.WITHIN_MAX }
                        ?: return "withinMin is not a whole number of minutes from 1 to ${AlertRule.WITHIN_MAX}"
                }
            }
        }
        return AlertRule(id, entity, kind, at, clearAt, value, forMin, words, repeatMin, severity, guard, direction, withinMin)
    }

    /** A `when` object: the guard, or why it is refused (a String). */
    private fun guard(el: JsonElement): Any {
        val o = el as? JsonObject ?: return "not a JSON object"
        val extra = o.keys - setOf("entity", "equals", "notEquals")
        if (extra.isNotEmpty()) return "${extra.sorted().joinToString()} does not apply (entity, and equals or notEquals)"
        val entity = text(o, "entity") ?: return "entity missing or not text"
        if (!HaReader.ENTITY.matches(entity)) return "entity \"$entity\" is not a hub entity id (domain.object_id)"
        val eq = o["equals"]
        val ne = o["notEquals"]
        if ((eq == null) == (ne == null)) return "needs exactly one of equals and notEquals"
        val value = ((eq ?: ne) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: return "${if (eq != null) "equals" else "notEquals"} is not a state"
        if (value in ABSENT) return "\"$value\" is no reading, so a guard on it could never decide"
        if (entity.startsWith(AlertRule.BUILT_IN_DOMAIN)) {
            val allowed = AlertRule.BUILT_IN_VALUES[entity]
                ?: return "\"$entity\" is not one of Damage's own values (${AlertRule.BUILT_IN_VALUES.keys.joinToString()})"
            if (value !in allowed) return "$entity is compared with ${allowed.joinToString(" or ")}, not \"$value\""
        }
        return Guard(entity, value, equals = eq != null)
    }

    private fun text(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A finite number; a number written as text is accepted (cast at the boundary). */
    private fun number(o: JsonObject, key: String): Double? =
        (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }

    /** Absent = 0; else a whole number ≥ 0, or null (refused). */
    private fun minutes(o: JsonObject, key: String): Int? {
        val el: JsonElement = o[key] ?: return 0
        return (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull?.takeIf { it >= 0 }
    }
}
