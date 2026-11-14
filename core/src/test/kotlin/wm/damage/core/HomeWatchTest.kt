package wm.damage.core

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.bastion.AlertDelivery
import wm.damage.core.bastion.HomeWatch
import wm.damage.core.bastion.NetFacts
import wm.damage.core.shell.ShellSettings

/**
 * Build W, package C (R6 T11, pins W13 and W14; the spec's §2(a) row "home unreachable"): the judge speaks only
 * while the phone has a validated, unblocked network; coming online after hours offline does not raise at once;
 * one raise per episode; a heard frame clears it; inside quiet hours a raise is held and released once if home is
 * still down, and a clear of a raise never said is dropped. All on an injected clock; no network.
 */
class HomeWatchTest {

    private class Net(override var validated: Boolean = true, override var blocked: Boolean = false,
        override var validatedSince: Long = 0L, override var vpnPresent: Boolean? = true) : NetFacts

    private class Rig(settings: ShellSettings = ShellSettings(), start: Long = NOON) {
        val d = DeliveryRig(settings, start = start)
        val heard = AtomicLong(0)
        val net = Net()
        @Volatile var threshold = 10
        val watch = HomeWatch({ heard.get() }, net, { threshold }, d.delivery, d.clock::get, startMs = start)
        fun at(ms: Long) { d.clock.set(ms); watch.tick() }
        fun home() = d.said(AlertDelivery.HOME_THREAD)
    }

    private val min = 60_000L

    @Test
    fun silentWhileOfflineOrBlocked() {
        val r = Rig()
        r.net.validated = false
        for (m in 1..120) r.at(NOON + m * min)
        assertEquals(0, r.home().size, "offline for two hours: nothing said")
        assertEquals(0, r.d.phone.homeShows.size)
        r.net.validated = true; r.net.blocked = true; r.net.validatedSince = NOON
        for (m in 121..240) r.at(NOON + m * min)
        assertEquals(0, r.home().size, "a blocked network counts as offline")
        assertFalse(r.watch.raised)
    }

    @Test
    fun comingOnlineAfterHoursOfflineStartsTheClockThen() {
        val r = Rig()
        r.net.validated = false
        r.at(NOON + 180 * min)
        r.net.validated = true; r.net.validatedSince = NOON + 180 * min       // online at last, nothing heard yet
        r.at(NOON + 181 * min)
        assertEquals(0, r.home().size, "online one minute: the clock starts at validatedSince, not at the start")
        r.at(NOON + 189 * min)
        assertEquals(0, r.home().size)
        r.at(NOON + 190 * min)
        assertEquals(1, r.home().size, "ten minutes online without a frame")
        assertEquals("home has not answered for 10 min (the phone has internet)", r.home().single().body)
    }

    @Test
    fun oneRaisePerEpisodeAndAHeardFrameClearsIt() {
        val r = Rig()
        r.heard.set(NOON + 1 * min)
        for (m in 2..60) r.at(NOON + m * min)
        assertEquals(1, r.home().size, "raised once in the episode, however many ticks")
        assertEquals(1, r.d.phone.homeShows.size)
        assertEquals("home has not answered for 10 min (the phone has internet)", r.home().single().body)
        r.at(NOON + 61 * min)
        assertEquals(1, r.home().size, "nothing heard: no clear")
        r.heard.set(NOON + 61 * min + 1)                                    // a frame after the raise
        r.at(NOON + 62 * min)
        assertEquals(2, r.home().size, "a heard frame clears it")
        assertEquals("home answers again after 60 min", r.home().last().body)
        assertEquals(1, r.d.phone.homeCancels.get(), "the shade's notification withdrawn")
        assertFalse(r.watch.raised)
        r.at(NOON + 63 * min)
        assertEquals(2, r.home().size, "heard: the next episode needs ten more minutes")
    }

    @Test
    fun theVpnClauseAndTheRowOff() {
        val r = Rig()
        r.net.vpnPresent = false
        r.at(NOON + 10 * min)
        assertEquals("home has not answered for 10 min (the phone has internet) · the phone's VPN is off", r.home().single().body)
        r.threshold = 0
        r.at(NOON + 11 * min)
        assertFalse(r.watch.raised, "the row turned off withdraws it")
        assertEquals(1, r.d.phone.homeCancels.get())
        assertEquals(1, r.home().size, "withdrawn silently")
        r.at(NOON + 200 * min)
        assertEquals(1, r.home().size, "off: nothing raises")
    }

    @Test
    fun heldInQuietHoursAndReleasedOnceIfStillDown() {
        val night = NOON + 11 * 60 * min                                    // 23:00 UTC, inside 22-07
        val r = Rig(ShellSettings(quietHours = "22-07"), start = night)
        r.at(night + 10 * min)
        assertTrue(r.watch.raised && r.watch.held, "raised and held")
        assertEquals(0, r.home().size, "inside quiet hours: nothing on the glasses")
        assertEquals(0, r.d.phone.homeShows.size, "nor in the shade")
        r.at(night + 300 * min)                                             // 04:00, still inside
        assertEquals(0, r.home().size)
        r.at(night + 480 * min)                                             // 07:00: the window has ended
        assertEquals(1, r.home().size, "released once")
        assertEquals("home has not answered since 23:00 (the phone has internet)", r.home().single().body)
        assertEquals(1, r.d.phone.homeShows.size)
        r.at(night + 481 * min)
        r.at(night + 482 * min)
        assertEquals(1, r.home().size, "released ONCE")
        assertFalse(r.watch.held)
    }

    @Test
    fun aHeldRaiseThatClearsInsideQuietHoursSaysNothing() {
        val night = NOON + 11 * 60 * min
        val r = Rig(ShellSettings(quietHours = "22-07"), start = night)
        r.at(night + 10 * min)
        assertTrue(r.watch.held)
        r.heard.set(night + 20 * min)
        r.at(night + 21 * min)
        assertFalse(r.watch.raised, "home answered")
        r.heard.set(night + 479 * min)                                      // and keeps answering through the night
        r.at(night + 480 * min)
        assertEquals(0, r.home().size, "never said, so neither the raise nor the clear is said")
        assertEquals(0, r.d.phone.homeShows.size)
    }
}
