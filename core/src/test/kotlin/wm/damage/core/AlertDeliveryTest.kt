package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import wm.damage.core.bastion.AlertDelivery
import wm.damage.core.bastion.AlertDelivery.Kind
import wm.damage.core.bastion.AlertDelivery.Rows
import wm.damage.core.bastion.AlertDelivery.Verdict
import wm.damage.core.bastion.AlertRecord
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.Severity
import wm.damage.core.shell.ShellSettings

/**
 * Build W, package C (R6 T14; the lead's decisions B.1–B.3): each row gates only its own path — `Notify · alerts`
 * the glasses' raise and repeat, `Notify · cleared` the glasses' clear, the phone-owned `Phone · alerts` the shade —
 * and Global `Phone notifications` gates nothing here (never the host's urgent hook either). The body is
 * `[urgent · ]text · value · HH:MM`, never cut. The pure decision's table.
 */
class AlertDeliveryTest {

    private fun rec(id: String = "fresh_full", sev: Severity = Severity.NORMAL, state: AlertState = AlertState.RAISED,
        text: String = "Fresh tank nearly full", value: String = "91") =
        AlertRecord(id, 3, sev, NOON - 3_600_000, state, 0, text, value, 0, false)     // raised 11:00 UTC

    @Test
    fun eachRowGatesOnlyItsOwnPath() {
        val r = DeliveryRig()
        r.delivery.alert(rec(), Kind.RAISED)
        assertEquals(1, r.glasses.size); assertEquals(1, r.phone.loud().size)

        r.settings = ShellSettings(notifyAlerts = false)
        r.delivery.alert(rec(), Kind.REPEAT)
        assertEquals(1, r.glasses.size, "Notify · alerts off: no glasses notice")
        assertEquals(2, r.phone.loud().size, "…and the shade still gets it")
        r.delivery.cleared(rec(), NOON, raiseSaid = true)
        assertEquals(2, r.glasses.size, "Notify · alerts does not gate the clear")
        assertEquals("cleared · Fresh tank nearly full · 12:00", r.glasses.last().body)

        r.settings = ShellSettings(notifyCleared = false)
        r.delivery.cleared(rec(), NOON, raiseSaid = true)
        assertEquals(2, r.glasses.size, "Notify · cleared off: no clear notice")
        r.delivery.alert(rec(), Kind.RAISED)
        assertEquals(3, r.glasses.size, "Notify · cleared does not gate a raise")

        r.settings = ShellSettings()
        r.phoneOn = false
        r.delivery.alert(rec(), Kind.RAISED)
        assertEquals(4, r.glasses.size, "Phone · alerts off leaves the glasses alone")
        assertEquals(3, r.phone.loud().size, "…and the shade gets nothing")
        r.delivery.cleared(rec(), NOON, raiseSaid = true)
        assertTrue(r.phone.cancels.isNotEmpty(), "a clear always withdraws the shade's notification")
        assertTrue(r.glasses.all { it.gatedBy == "Bastion" && it.source == "bastion" })
    }

    /** Fix round 1 (review 1, note 8): turning `Phone · alerts` off withdraws the bus notifications from the shade at
     *  once ("home unreachable" too); on again posts every RAISED alert silently — nothing loud, nothing on the
     *  glasses. */
    @Test
    fun thePhoneRowReconcilesTheShadeAtOnce() {
        val r = DeliveryRig()
        r.delivery.alert(rec("fresh_full"), Kind.RAISED)
        r.delivery.alert(rec("pump_fault", Severity.URGENT), Kind.RAISED)
        assertEquals(setOf("fresh_full", "pump_fault"), r.phone.shown.toSet())
        val glasses = r.glasses.size
        val loud = r.phone.loud().size

        r.phoneOn = false
        r.delivery.phoneRowChanged(false, listOf(rec("fresh_full"), rec("pump_fault", Severity.URGENT)))
        assertEquals(emptySet(), r.phone.shown.toSet(), "off: the shade's bus notifications are withdrawn")
        assertEquals(1, r.phone.homeCancels.get(), "…and home unreachable's")

        r.phoneOn = true
        r.delivery.phoneRowChanged(true, listOf(rec("fresh_full"), rec("pump_fault", Severity.URGENT)))
        assertEquals(setOf("fresh_full", "pump_fault"), r.phone.shown.toSet(), "on: every RAISED alert posted again")
        assertEquals(loud, r.phone.loud().size, "silently")
        assertEquals(glasses, r.glasses.size, "and nothing on the glasses")
    }

    @Test
    fun phoneNotificationsGatesNothingHere() {
        for (pn in listOf(false, true)) {
            val r = DeliveryRig(ShellSettings(phoneNotifications = pn))
            r.delivery.alert(rec(), Kind.RAISED)
            r.delivery.alert(rec(sev = Severity.URGENT), Kind.RAISED)
            r.delivery.acked(rec(state = AlertState.ACKED))
            assertEquals(2, r.phone.loud().size, "Phone notifications=$pn: the shade gets both raises")
            assertEquals(1, r.phone.posts.count { it.silent && it.acked }, "and the acknowledgement")
        }
    }

    @Test
    fun throughTheShellNeverTheUrgentHook(): Unit = runBlocking {
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            r.shell.start(); r.settle("start")
            val phone = FakePhone()
            val d = AlertDelivery(r.shell, phone) { true }
            d.alert(rec(sev = Severity.URGENT, id = "pump_fault", text = "Water pump fault", value = "on"), Kind.RAISED)
            waitUntil(10_000, 10, { "the notice is up (${r.posted()})" }) { r.posted().any { it.thread == "pump_fault" } }
            val n = r.posted().single { it.thread == "pump_fault" }
            assertTrue(n.body.startsWith("urgent · Water pump fault · on · "), n.body)
            assertEquals("Bastion", n.gatedBy)
            assertEquals(0, r.urgent.get(), "never the host's urgent hook")
            assertEquals(1, phone.loud().size)
            r.shell.stop()
        }
    }

    @Test
    fun theBodyIsWholeAndInTheDevicesZone() {
        val r = DeliveryRig()
        val long = "Grey tank above the line it should never reach, check the valve under the sink before anything else"
        r.delivery.alert(rec(text = long), Kind.RAISED)
        assertEquals("$long · 91 · 11:00", r.glasses.single().body, "never cut")
        r.delivery.alert(rec(sev = Severity.URGENT, value = ""), Kind.RELEASE)
        assertEquals("urgent · Fresh tank nearly full · since 11:00", r.glasses.last().body, "no empty value segment")
        r.zone = java.time.ZoneId.of("America/Chicago")
        r.delivery.alert(rec(), Kind.RAISED)
        assertEquals("Fresh tank nearly full · 91 · 05:00", r.glasses.last().body, "HH:MM in the device's zone")
        r.delivery.acked(rec(state = AlertState.ACKED))
        assertEquals("acknowledged · Fresh tank nearly full · 91 · 05:00", r.phone.posts.last().body)
    }

    @Test
    fun theDecisionTable() {
        val all = Rows(notifyAlerts = true, notifyCleared = true, phoneAlerts = true)
        val N = Severity.NORMAL
        val U = Severity.URGENT
        assertEquals(Verdict(false, true, true), AlertDelivery.decide(Kind.RAISED, N, all, quiet = false))
        assertEquals(Verdict(true, false, false), AlertDelivery.decide(Kind.RAISED, N, all, quiet = true))
        assertEquals(Verdict(true, false, false), AlertDelivery.decide(Kind.REPEAT, N, all, quiet = true))
        assertEquals(Verdict(false, true, true), AlertDelivery.decide(Kind.RAISED, U, all, quiet = true), "urgent passes")
        assertEquals(Verdict(false, true, true), AlertDelivery.decide(Kind.REPEAT, U, all, quiet = true))
        assertEquals(Verdict(false, true, true), AlertDelivery.decide(Kind.RELEASE, N, all, quiet = false))
        assertEquals(Verdict(false, false, false), AlertDelivery.decide(Kind.CLEARED, N, all, quiet = true), "a quiet clear is dropped")
        assertEquals(Verdict(false, true, false), AlertDelivery.decide(Kind.CLEARED, U, all, quiet = true))
        assertEquals(Verdict(false, true, false), AlertDelivery.decide(Kind.CLEARED, N, all, quiet = false))
        assertEquals(Verdict(true, false, false), AlertDelivery.decide(Kind.ADOPT, N, all, quiet = true))
        assertEquals(Verdict(false, false, true), AlertDelivery.decide(Kind.ADOPT, N, all, quiet = false), "adopted: the shade only")
        assertEquals(Verdict(false, false, true), AlertDelivery.decide(Kind.ACKED, N, all, quiet = true))
        val none = Rows(false, false, false)
        for (k in Kind.entries) for (q in listOf(false, true)) {
            val v = AlertDelivery.decide(k, U, none, q)
            assertTrue(!v.glasses && !v.phone, "all rows off: nothing delivered ($k, quiet=$q)")
        }
    }
}
