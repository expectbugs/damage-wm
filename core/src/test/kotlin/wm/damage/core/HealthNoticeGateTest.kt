package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.HealthCore
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.Notices
import wm.damage.core.shell.ShellSettings

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b H2, the pin of the lead's ruling (`private/build085/RB-decisions.md` Part B): the health record's notice is
 * Damage's own, so it obeys Global `Notify · Damage` on the glasses — it names the category Global, and before H2 the
 * shell's one gate passed any notice that named a category without asking that row. Watched failing on the tree
 * without the fix (the report says where). A host category's notice (the bus watcher's "Bastion") still passes:
 * `BastionGateTest`. Made-up notices; a shell on the simulator.
 */
class HealthNoticeGateTest {

    /** The health record's disk notice as the PC's notice record holds it: no app, the category Global. */
    private fun diskNotice(): NoticeRecord = Notices.parse("${HealthCore.SOURCE}:${HealthCore.DISK}", buildJsonObject {
        put("occ", "1"); put("raisedAt", 1_800_000_000_000L); put("source", HealthCore.SOURCE)
        put("body", "Disk: 1.6 % free of 3.6 TB since 14:02. Shown only: nothing is cleaned automatically.")
        put("urgency", Notices.NORMAL); put("gatedBy", HealthCore.GATED_BY); put("state", Notices.RAISED)
    })!!

    private fun shown(notifyDamage: Boolean): List<String> = runBlocking {
        ShellRig(ShellSettings(notifyDamage = notifyDamage)).use { r ->
            r.shell.start(); r.settle("start")
            r.shell.noticeFromRecord(diskNotice())
            r.settle("the record's notice")
            r.posted().map { it.body }.also { r.shell.stop() }
        }
    }

    @Test
    fun aHealthNoticeIsNotShownWithNotifyDamageOff() {
        assertEquals(emptyList(), shown(notifyDamage = false), "Notify · Damage off: the health notice stays off the glasses")
    }

    @Test
    fun aHealthNoticeIsShownWithNotifyDamageOn() {
        assertEquals(listOf(diskNotice().body), shown(notifyDamage = true), "Notify · Damage on: it is shown as raised")
    }
}
