package wm.damage.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.Contracts
import wm.damage.core.shell.ActivationSource

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 — the phone's arrangement of Reader (`CONTRACT.md` §11.1; the spec's §2 P4): the glasses'
 * presenter over the phone's working copy, fed by the `app` lane from a PC record on loopback, the books through
 * `RemoteContent`. A place read on the glasses reaches the PC record; a place another client writes reaches the
 * glasses at the next entry, and not before. Replaces `SubstrateTest.continuityBookPositionFollowsAcrossShells`
 * (dec. A.13): a book's place no longer crosses the sync channel.
 */
class ReaderArrangementsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    @Test
    fun aGlassesPlaceOnThePhoneReachesThePcRecord_andAnotherClientsPlaceReachesTheGlassesAtTheNextEntry(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, srv.boundPort, services)
        readerAwait("the lane is up with the same Reader build") { wc.client?.contractState?.shared(wm.damage.core.windows.reader.ReaderCore.WINDOW) == true }
        assertNull(wc.client?.contractState?.line(wm.damage.core.windows.reader.ReaderCore.WINDOW))
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("the book opens from the PC's bytes (${g.title()})") { g.title().contains("p.") }

        // read on the glasses; the save point sends the place
        services.onLoop { g.doc().topLine = 15 }
        val copy = services.onLoop { g.saveSubState() }
        val local = copy["book.${ids[0]}"]!!["off"]!!.jsonPrimitive.intOrNull!!
        readerAwait("the glasses' place reached the PC record") { rig.placeOffset(h, ids[0]) == local }
        assertEquals("phone", rig.place(h, ids[0])!!.by)
        readerAwait("the phone's queue drained") { wc.queue.waiting == 0 }
        // the book read most recently anywhere: set by the open
        readerAwait("the open set the most recent book") {
            h.read(wm.damage.core.windows.reader.ReaderCore.WINDOW).third.items["rec.reader.recent"]?.value?.jsonPrimitive?.content == ids[0]
        }

        // another client reads on, far ahead
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, ids[0], 9_000) is Answer.Seen)
        readerAwait("the web's place reached the phone's copy") { stateOffset(g.source.state(), ids[0]) == 9_000 }
        delay(200)
        assertEquals(15, g.doc().topLine, "nothing moves the page without input on it")
        assertEquals("Go there", services.onLoop { g.actionLabels() }.first())

        // the next entry takes it, and writes nothing back
        services.onLoop { g.onActivate(services, ActivationSource.SWITCHER) }
        assertNotEquals(15, g.doc().topLine)
        services.onLoop { g.saveSubState() }
        delay(300)
        assertEquals(9_000, rig.placeOffset(h, ids[0]))
        assertEquals(0, wc.queue.waiting)
        assertTrue(services.notices.none { "was not applied" in it }, "${services.notices}")
    }

    @Test
    fun theOtherBooksPlacesFollowTheRecordAtOnce_theyAreNotOnScreen(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, srv.boundPort, services)
        readerAwait("the lane is up") { wc.client?.contractState is Contracts.State.Known }
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, ids[1], 4_321) is Answer.Seen)
        readerAwait("the second book's place is the glasses' own now") {
            services.onLoop { g.saveSubState() }["book.${ids[1]}"]?.get("off")?.jsonPrimitive?.intOrNull == 4_321
        }
        // and it is not sent back
        delay(300)
        assertEquals("web", rig.place(h, ids[1])!!.by)
    }
}
