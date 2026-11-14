package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.bastion.AlertRule
import wm.damage.core.bastion.RuleKind
import wm.damage.core.bastion.RulesFile
import wm.damage.core.bastion.Severity

/**
 * Build W, package A (R6 T4 as amended): a bad rule refuses only itself, said with its id and why, and the rest
 * run; severity and the watcher's own prefix are checked; an urgent rule's `repeatMin` is ignored; a missing file
 * and a file that is not JSON are told apart. Made-up entity ids only.
 */
class RuleParseTest {

    private fun refusedIds(text: String) = RulesFile.parse(text).refused.map { it.id }

    @Test
    fun aBadRuleRefusesOnlyItself() {
        val load = RulesFile.parse("""
            {"rules": [
              {"id": "fresh_low", "entity": "sensor.fresh_tank", "kind": "below", "at": 15, "clearAt": 25,
               "forMin": 2, "text": "Fresh water low", "repeatMin": 60},
              {"id": "bad_kind", "entity": "sensor.fresh_tank", "kind": "sideways", "text": "x"},
              {"id": "pump", "entity": "binary_sensor.pump_fault", "kind": "equals", "value": "on",
               "text": "Pump fault", "severity": "urgent"},
              {"id": "backwards", "entity": "sensor.fresh_tank", "kind": "above", "at": 80, "clearAt": 90, "text": "x"},
              "not an object",
              {"entity": "sensor.fresh_tank", "kind": "unavailable", "forMin": 5, "text": "no id"}
            ]}
        """.trimIndent())
        assertNull(load.fileProblem)
        assertFalse(load.missing)
        assertEquals(listOf("fresh_low", "pump"), load.rules.map { it.id })
        assertEquals(listOf("bad_kind", "backwards", "#5", "#6"), load.refused.map { it.id })
        assertTrue(load.refused.all { it.why.isNotBlank() })
        assertTrue("sideways" in load.refused[0].why, load.refused[0].why)

        val low = load.rules[0]
        assertEquals(AlertRule("fresh_low", "sensor.fresh_tank", RuleKind.BELOW, at = 15.0, clearAt = 25.0,
            forMin = 2, text = "Fresh water low", repeatMin = 60), low)
        assertEquals(Severity.NORMAL, low.severity)
        assertEquals(3_600_000L, low.repeatMs)
        assertEquals(120_000L, low.forMs)
        assertEquals(Severity.URGENT, load.rules[1].severity)
        assertEquals("on", load.rules[1].value)
    }

    @Test
    fun severityAndTheWatchersPrefixAreChecked() {
        assertEquals(listOf("loud"), refusedIds("""{"rules":[{"id":"loud","entity":"sensor.fresh_tank",
            "kind":"unavailable","forMin":5,"text":"x","severity":"loud"}]}"""))
        assertEquals(listOf("watcher.hub"), refusedIds("""{"rules":[{"id":"watcher.hub","entity":"sensor.fresh_tank",
            "kind":"unavailable","forMin":5,"text":"x"}]}"""))
        // the second rule with an id is refused, the first runs
        val dup = RulesFile.parse("""{"rules":[
            {"id":"a","entity":"sensor.fresh_tank","kind":"unavailable","forMin":5,"text":"one"},
            {"id":"a","entity":"sensor.fresh_tank","kind":"stale","forMin":5,"text":"two"}]}""")
        assertEquals(listOf("one"), dup.rules.map { it.text })
        assertEquals(listOf("a"), dup.refused.map { it.id })
        assertTrue("#1" in dup.refused[0].why)
    }

    @Test
    fun anUrgentRulesRepeatMinIsIgnored() {
        val load = RulesFile.parse("""{"rules":[
            {"id":"u0","entity":"sensor.fresh_tank","kind":"unavailable","forMin":5,"text":"x","severity":"urgent"},
            {"id":"u3","entity":"sensor.fresh_tank","kind":"unavailable","forMin":5,"text":"x","severity":"urgent","repeatMin":3},
            {"id":"n0","entity":"sensor.fresh_tank","kind":"unavailable","forMin":5,"text":"x"},
            {"id":"n3","entity":"sensor.fresh_tank","kind":"unavailable","forMin":5,"text":"x","repeatMin":3}]}""")
        assertEquals(emptyList(), load.refused)
        assertEquals(listOf(600_000L, 600_000L, 0L, 180_000L), load.rules.map { it.repeatMs })
    }

    @Test
    fun eachKindsFieldsAreChecked() {
        fun one(fields: String) = refusedIds("""{"rules":[{"id":"r","entity":"sensor.fresh_tank","text":"x",$fields}]}""")
        assertEquals(emptyList(), one(""""kind":"above","at":80,"clearAt":70"""))
        assertEquals(listOf("r"), one(""""kind":"above","at":80,"clearAt":80"""), "above needs clearAt below at")
        assertEquals(listOf("r"), one(""""kind":"below","at":20,"clearAt":10"""), "below needs clearAt above at")
        assertEquals(listOf("r"), one(""""kind":"above","at":80"""), "clearAt missing")
        assertEquals(listOf("r"), one(""""kind":"above","at":"high","clearAt":70"""), "at not a number")
        assertEquals(emptyList(), one(""""kind":"above","at":"80","clearAt":70"""), "a number written as text is cast")
        assertEquals(listOf("r"), one(""""kind":"equals""""), "value missing")
        assertEquals(emptyList(), one(""""kind":"notEquals","value":"on""""))
        assertEquals(listOf("r"), one(""""kind":"unavailable""""), "unavailable needs forMin")
        assertEquals(listOf("r"), one(""""kind":"stale","forMin":0"""), "stale needs forMin above 0")
        assertEquals(listOf("r"), one(""""kind":"stale","forMin":2.5"""), "forMin whole minutes")
        assertEquals(listOf("r"), one(""""kind":"stale","forMin":5,"repeatMin":-1"""), "repeatMin 0 or more")
        assertEquals(listOf("r"), one(""""kind":"stale","forMin":5,"at":3"""), "a field that does not apply")
        assertEquals(listOf("r"), one(""""kind":"stale","forMins":5"""), "an unknown key is refused, never ignored")
        assertEquals(emptyList(), one(""""kind":"stale","forMin":5,"note":"a free comment""""))
        assertEquals(listOf("r"), refusedIds("""{"rules":[{"id":"r","entity":"sensor.fresh_tank","kind":"stale","forMin":5,"text":"  "}]}"""),
            "a notice needs words")
        assertEquals(listOf("r"), refusedIds("""{"rules":[{"id":"r","entity":"sensor.a/b","kind":"stale","forMin":5,"text":"x"}]}"""),
            "an entity id that is not domain.object_id")
        assertEquals(listOf("#1"), refusedIds("""{"rules":[{"id":"Bad Id","entity":"sensor.fresh_tank","kind":"stale","forMin":5,"text":"x"}]}"""))
    }

    @Test
    fun aMissingFileAndAFileThatIsNotJsonAreToldApart() {
        val dir = Files.createTempDirectory("damage-rules")
        try {
            val missing = RulesFile.load(dir.resolve("bastion-rules.json"))
            assertTrue(missing.missing)
            assertNull(missing.fileProblem)
            assertEquals(0, missing.rules.size)

            val notJson = dir.resolve("not-json.json").also { Files.writeString(it, "{\"rules\": [ oops") }
            val a = RulesFile.load(notJson)
            assertFalse(a.missing)
            assertNotNull(a.fileProblem)
            assertEquals(0, a.rules.size)

            val noList = dir.resolve("no-list.json").also { Files.writeString(it, "{\"rulez\": []}") }
            assertNotNull(RulesFile.load(noList).fileProblem)

            val good = dir.resolve("good.json").also {
                Files.writeString(it, """{"rules":[{"id":"r","entity":"sensor.fresh_tank","kind":"stale","forMin":5,"text":"x"}]}""")
            }
            val g = RulesFile.load(good)
            assertNull(g.fileProblem)
            assertEquals(listOf("r"), g.rules.map { it.id })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
