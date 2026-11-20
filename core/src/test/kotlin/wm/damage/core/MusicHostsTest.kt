package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Admit
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HostBackend
import wm.damage.core.app.Item
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.OpQueue
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState
import wm.damage.core.net.WinService
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.music.AudioProfile
import wm.damage.core.windows.music.Catalog
import wm.damage.core.windows.music.Lyrics
import wm.damage.core.windows.music.Mode
import wm.damage.core.windows.music.MusicCore
import wm.damage.core.windows.music.MusicLibrary
import wm.damage.core.windows.music.MusicMigration
import wm.damage.core.windows.music.MusicPlayer
import wm.damage.core.windows.music.MusicProgram
import wm.damage.core.windows.music.MusicService
import wm.damage.core.windows.music.NOT_KNOWN
import wm.damage.core.windows.music.Output
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.PlayerCore
import wm.damage.core.windows.music.PlayerEvent
import wm.damage.core.windows.music.Playlist
import wm.damage.core.windows.music.QueueEngine
import wm.damage.core.windows.music.ResolvedQueue
import wm.damage.core.windows.music.SimMusicPlayer
import wm.damage.core.windows.music.TrackMeta
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.windows.music.YtJob
import wm.damage.core.windows.music.YtResult

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-4 K (the lean spec's §4): Music's two players behind one transport, with no audio — two stand-in players
 * (`SimMusicPlayer`'s sink) on one PC record through two backends (devices `local` and `phone`), their verbs carried
 * by a thread standing in for the network. The rule, moves both ways, a failed and a silent target, the stop when
 * `out` moves, the routing, the restart write, the away take-over, the relay over a fake push, the migration on APK
 * 0.77's shapes. No test starts a player program or opens a sound device.
 */
class MusicHostsTest {
    private val dir = Files.createTempDirectory("damage-music-hosts")
    @Volatile private var t = 1_700_000_000_000L
    private val clock: () -> Long = { t }
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-music"),
        clock = clock).also { it.open() }
    private val net1 = Executors.newSingleThreadExecutor { r -> Thread(r, "net-to-pc").apply { isDaemon = true } }
    private val net2 = Executors.newSingleThreadExecutor { r -> Thread(r, "net-to-phone").apply { isDaemon = true } }

    @AfterTest
    fun tearDown() {
        net1.shutdownNow(); net2.shutdownNow()
        host.close()
        dir.toFile().deleteRecursively()
    }

    private fun source(device: String) = LocalWindowSource(MusicCore(), HostBackend(host, device, OpQueue.newStream()))
    private fun rec(): WindowState = WindowState(MusicCore.WINDOW, host.read(MusicCore.WINDOW).third.items)

    /** The events the last [hosts] heard on the phone, said with a wait that fails. */
    @Volatile private var heard: List<PlayerEvent> = emptyList()

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("never became true: $what; the phone heard ${heard.toList()}")
            Thread.sleep(5)
        }
    }

    private class Hosts(val phone: SimMusicPlayer, val pc: SimMusicPlayer, val events: MutableList<PlayerEvent>)

    /** The phone's verbs reach the PC's door; the PC's relay reaches the phone's player. [silentPc]: the PC never
     *  answers; [pcLib]: the PC's own library (one that cannot open, for a failed take-over). */
    private fun hosts(pcLib: MusicLibrary = Lib(), silentPc: Boolean = false, pcUnreachable: Boolean = false,
                      phoneLib: MusicLibrary = Lib(), phoneSpotify: String? = null): Hosts {
        var program: MusicProgram? = null
        var phone: SimMusicPlayer? = null
        val toPc = PlayerCore.Remote { verb, args, done ->
            when {
                pcUnreachable -> done("host is not reachable ('music')", true)
                silentPc -> {}
                else -> net1.execute { program!!.act(verb, args, MusicCore.PHONE) { why -> done(why, false) } }
            }
        }
        val toPhone = PlayerCore.Remote { verb, args, done -> net2.execute { phone!!.command(verb, args) { why -> done(why, false) } } }
        val pc = SimMusicPlayer(pcLib, clock, source = source("local"), host = MusicCore.PC, remote = toPhone)
        phone = SimMusicPlayer(phoneLib, clock, spotify = phoneSpotify, source = source("phone"), host = MusicCore.PHONE, remote = toPc)
        program = MusicProgram(pc, { v, a, d -> toPhone.send(v, a, d) }, { true }, clock)
        val events = java.util.Collections.synchronizedList(ArrayList<PlayerEvent>())
        phone.addListener(object : MusicPlayer.Listener { override fun event(e: PlayerEvent) { events += e } })
        heard = events
        return Hosts(phone, pc, events)
    }

    private fun tracks() = Lib().tracks.map { it.ref() }

    @Test
    fun theRule_queueAndPlaceOnlyFromThePlayingHost_outOnlyFromTheHostItNames() {
        fun op(device: String, action: String, target: String, v: JsonObject) =
            Op(device, OpId.Queued("s", 1), MusicCore.WINDOW, action, listOf(target), v, at = 1L)
        fun state(out: String?) = WindowState(MusicCore.WINDOW, if (out == null) emptyMap() else
            mapOf(Record.key(MusicCore.WINDOW, MusicCore.OUT) to Item(MusicCore.Out(out, 1L).json(), "local", 1L)))
        val q = MusicCore.queueJson(emptyList(), Mode.QUEUE, "")
        assertNull(MusicCore.RULE.admit(state(null), op("phone", MusicCore.A_QUEUE, MusicCore.Q, q)), "absent out = the phone plays")
        assertEquals(Admit.Keep, MusicCore.RULE.admit(state(null), op("local", MusicCore.A_QUEUE, MusicCore.Q, q)))
        assertNull(MusicCore.RULE.admit(state(MusicCore.PC), op("local", MusicCore.A_NOW, MusicCore.NOW, q)))
        assertEquals(Admit.Keep, MusicCore.RULE.admit(state(MusicCore.PC), op("phone", MusicCore.A_QUEUE, MusicCore.Q, q)),
            "the phone's queue written while away does not overwrite the PC's")
        assertTrue(MusicCore.RULE.admit(state(null), op("phone", MusicCore.A_OUT, MusicCore.OUT, MusicCore.Out(MusicCore.PC, 2).json())) is Admit.Refuse)
        assertNull(MusicCore.RULE.admit(state(null), op("local", MusicCore.A_OUT, MusicCore.OUT, MusicCore.Out(MusicCore.PC, 2).json())))
        assertNull(MusicCore.RULE.admit(state(MusicCore.PC), op("migration", MusicCore.MIGRATE, MusicCore.Q, q)), "the migration passes")
    }

    @Test
    fun aMoveToThePcAndBack_playsOnOneHostAtATime() {
        val h = hosts()
        h.phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        await("the phone's queue and place in the record") { MusicCore.queueOf(rec())?.entries?.size == 4 && MusicCore.nowOf(rec())?.playing == true }
        assertEquals(MusicCore.PHONE, MusicCore.outOf(rec()).host)
        assertTrue(h.pc.opened.isEmpty(), "the PC opened nothing while the phone plays")
        t += 30_000
        h.phone.setOutput(Output.PC)
        await("the PC plays") { MusicCore.outOf(rec()).host == MusicCore.PC && h.pc.state.play == PlayState.PLAYING }
        assertEquals("1@30000", h.pc.opened.single(), "the PC opened the phone's entry at its exact place")
        await("the phone stopped") { h.phone.state.play == PlayState.PLAYING && h.phone.cmds.contains("stop") }
        assertEquals(PlayState.PLAYING, h.phone.state.play, "the phone shows the PC playing")
        assertEquals(Output.PC, h.phone.state.output)
        val phoneOpens = h.phone.opened.size
        t += 10_000
        h.pc.setOutput(Output.PHONE)
        await("the phone plays again") { MusicCore.outOf(rec()).host == MusicCore.PHONE && h.phone.opened.size == phoneOpens + 1 }
        assertEquals("1@40000", h.phone.opened.last(), "the phone took the PC's place")
        await("the PC stopped") { h.pc.cmds.contains("stop") && h.pc.state.output == Output.PHONE }
    }

    @Test
    fun aTargetThatCannotOpen_leavesTheOutputAndThePhonePlaying() {
        val h = hosts(pcLib = Lib(url = "fail://"))
        h.phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        await("playing") { MusicCore.nowOf(rec())?.playing == true }
        h.phone.setOutput(Output.PC)
        await("the refusal said on the phone") { h.events.any { it is PlayerEvent.Error } }
        assertEquals(MusicCore.PHONE, MusicCore.outOf(rec()).host)
        assertEquals(PlayState.PLAYING, h.phone.state.play)
        assertTrue("stop" !in h.phone.cmds)
    }

    @Test
    fun offThePlayingHost_verbsGoThere_andNothingSoundsHere() {
        val h = hosts()
        h.phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        h.phone.setOutput(Output.PC)
        await("the PC plays") { MusicCore.outOf(rec()).host == MusicCore.PC && h.phone.cmds.contains("stop") }
        val cmds = h.phone.cmds.size
        val opens = h.phone.opened.size
        h.phone.next()
        await("the PC's next") { h.pc.state.index == 1 }
        await("the phone shows it") { h.phone.state.index == 1 }
        h.phone.pause()
        await("the PC paused") { h.pc.state.play == PlayState.PAUSED }
        await("the phone shows paused") { h.phone.state.play == PlayState.PAUSED }
        h.phone.play()
        await("the PC plays again") { h.pc.state.play == PlayState.PLAYING }
        assertEquals(opens, h.phone.opened.size, "the phone opened nothing")
        assertEquals(cmds, h.phone.cmds.size, "the phone's sink got no call")
    }

    @Test
    fun aStartNeverPlays_andWritesTheRestartStop() {
        val pc0 = source("local")
        val pc = SimMusicPlayer(Lib(), clock, source = pc0, host = MusicCore.PC)
        // the PC wrote out and a playing place, then its process ended (the record as an unclean stop leaves it)
        pc0.act(wm.damage.core.app.Draft(MusicCore.A_OUT, listOf(MusicCore.OUT), MusicCore.Out(MusicCore.PC, t).json()))
        val q = MusicCore.queueJson(listOf(wm.damage.core.windows.music.QueueEntry(42L, tracks()[0])), Mode.QUEUE, "L")
        pc0.act(wm.damage.core.app.Draft(MusicCore.A_QUEUE, listOf(MusicCore.Q), q))
        pc0.act(wm.damage.core.app.Draft(MusicCore.A_NOW, listOf(MusicCore.NOW), MusicCore.Now(42L, 133_000, t, MusicCore.PLAYING, 80).json()))
        await("written") { MusicCore.nowOf(rec())?.playing == true }
        assertTrue(pc.opened.isEmpty() && "play" !in pc.cmds, "a record change made no sound on the playing host's own player")
        // the next start: a fresh player on the same record
        val pc2 = SimMusicPlayer(Lib(), clock, source = source("local"), host = MusicCore.PC)
        await("the restart stop") { MusicCore.nowOf(rec())?.state == MusicCore.STOPPED }
        assertTrue(MusicCore.nowOf(rec())!!.why!!.contains("Play resumes at 2:13"), "${MusicCore.nowOf(rec())}")
        assertTrue(pc2.opened.isEmpty() && "play" !in pc2.cmds)
    }

    @Test
    fun awayFromThePc_playOnThePhoneTakesTheOutput() {
        val h = hosts(pcUnreachable = true)
        val pcSrc = source("local")
        // the PC was the output, at 1:00 into the first entry, paused
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_OUT, listOf(MusicCore.OUT), MusicCore.Out(MusicCore.PC, t).json()))
        val q = MusicCore.queueJson(tracks().mapIndexed { i, tr -> wm.damage.core.windows.music.QueueEntry(100L + i, tr) }, Mode.QUEUE, "L")
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_QUEUE, listOf(MusicCore.Q), q))
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_NOW, listOf(MusicCore.NOW), MusicCore.Now(100L, 60_000, t, MusicCore.PAUSED, 50).json()))
        await("the phone follows the PC") { h.phone.state.output == Output.PC && h.phone.state.queue.size == 4 }
        h.phone.next()
        await("said, with the age") { h.events.any { it is PlayerEvent.Error && it.detail.startsWith("PC not answering since") } }
        h.phone.play()
        await("the phone took the output") { MusicCore.outOf(rec()).host == MusicCore.PHONE }
        assertEquals("1@60000", h.phone.opened.last())
        assertEquals("phone", host.read(MusicCore.WINDOW).third.items[Record.key(MusicCore.WINDOW, MusicCore.OUT)]?.by)
    }

    // ---- S1-4's fix stage (the review of half A): one pin per defect found ----

    @Test
    fun fix1_theAwayTakeOverOfAnUncachedTrack_endsWithThePhoneAsTheOutput() {
        val h = hosts(pcUnreachable = true, phoneLib = Lib(url = "stall://"), phoneSpotify = "Spot")
        val pcSrc = source("local")
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_OUT, listOf(MusicCore.OUT), MusicCore.Out(MusicCore.PC, t).json()))
        val q = MusicCore.queueJson(tracks().mapIndexed { i, tr -> wm.damage.core.windows.music.QueueEntry(100L + i, tr) }, Mode.QUEUE, "L")
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_QUEUE, listOf(MusicCore.Q), q))
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_NOW, listOf(MusicCore.NOW), MusicCore.Now(100L, 60_000, t, MusicCore.PLAYING, 50).json()))
        await("the phone follows the PC") { h.phone.state.output == Output.PC && h.phone.state.queue.size == 4 }
        h.phone.play()
        await("Spotify plays and the phone is the output") {
            h.phone.state.backend == wm.damage.core.windows.music.Backend.SPOTIFY && MusicCore.outOf(rec()).host == MusicCore.PHONE
        }
        assertEquals("phone", host.read(MusicCore.WINDOW).third.items[Record.key(MusicCore.WINDOW, MusicCore.OUT)]?.by)
    }

    @Test
    fun fix2_aMoveAwayFromSpotify_pausesItAndSendsTheLibrarysPlace() {
        val h = hosts(phoneSpotify = "Spot")
        h.phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        t += 20_000
        h.phone.pretendSpotifySession(true)                          // a Spotify he starts himself: the library pauses at 0:20
        await("Spotify followed") { h.phone.state.backend == wm.damage.core.windows.music.Backend.SPOTIFY }
        t += 50_000
        h.phone.setOutput(Output.PC)
        await("the PC plays") { MusicCore.outOf(rec()).host == MusicCore.PC && h.pc.opened.isNotEmpty() }
        assertEquals("1@20000", h.pc.opened.single(), "the library's place, never Spotify's offset")
        await("Spotify paused on the phone") { "spotify:pause" in h.phone.cmds }
        assertEquals(wm.damage.core.windows.music.Backend.LIBRARY, h.phone.state.backend)
    }

    @Test
    fun fix3_aMovesAnswerStopsTheSource_withoutTheRecord() {
        // the phone on a record of its own: the PC's `out` never reaches it, only the move's answer does
        val pc = SimMusicPlayer(Lib(), clock, source = source("local"), host = MusicCore.PC)
        val program = MusicProgram(pc, { _, _, d -> d("no phone", true) }, { true }, clock)
        val own = LocalWindowSource(MusicCore(), wm.damage.core.app.MemoryRecord(MusicCore.ACTIONS, "phone", clock = clock))
        val phone = SimMusicPlayer(Lib(), clock, source = own, host = MusicCore.PHONE,
            remote = { v, a, d -> net1.execute { program.act(v, a, MusicCore.PHONE) { w -> d(w, false) } } })
        phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        await("the phone's own record holds the queue") { MusicCore.queueOf(own.state())?.entries?.size == 4 }
        // the PC's record needs the queue to take over: the phone's own record is not the PC's here
        source("local").act(wm.damage.core.app.Draft(MusicCore.MIGRATE, listOf(MusicCore.Q), MusicCore.queueOf(own.state())!!.let {
            MusicCore.queueJson(it.entries, it.mode, it.label) }))
        await("the PC holds the queue") { pc.state.queue.size == 4 }
        phone.setOutput(Output.PC)
        await("the PC plays") { pc.state.play == PlayState.PLAYING }
        await("the phone stopped on the answer") { "stop" in phone.cmds }
    }

    @Test
    fun fix4_aFailureOnThePlayingHost_isSaidOnTheOther() {
        val h = hosts(pcLib = Lib(url = "fail://", only = 2))
        h.phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        h.phone.setOutput(Output.PC)
        await("the PC plays") { MusicCore.outOf(rec()).host == MusicCore.PC && h.phone.cmds.contains("stop") }
        h.phone.next()                                               // the PC cannot open track 2
        await("the reason, on the phone") { h.events.any { it is PlayerEvent.Error && it.detail.contains("cannot open fail://2") } }
        assertTrue(MusicCore.nowOf(rec())!!.why!!.contains("cannot open fail://2"))
    }

    @Test
    fun fix5_theMigratedQueueShows_onAPhoneThatBoundBeforeIt() {
        val phone = SimMusicPlayer(Lib(), clock, source = source("phone"), host = MusicCore.PHONE)
        Thread.sleep(50)
        assertTrue(phone.state.queue.isEmpty(), "bound to a record with no Music items yet")
        // the PC's migration lands afterwards (APK 0.77's shapes, as `theMigration_0_77Shapes_writeOnce` writes them)
        val m = MusicCore.Now(2L, 61_000, t, MusicCore.STOPPED, 70)
        val q = MusicCore.queueJson(listOf(wm.damage.core.windows.music.QueueEntry(1L, tracks()[0]), wm.damage.core.windows.music.QueueEntry(2L, tracks()[1])), Mode.QUEUE, "Pink Floyd")
        val pcSrc = source("local")
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.MIGRATE, listOf(MusicCore.Q), q))
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.MIGRATE, listOf(MusicCore.NOW), m.json()))
        await("the queue shows") { phone.state.queue.map { it.qid } == listOf(1L, 2L) && phone.state.index == 1 }
        assertTrue(phone.opened.isEmpty(), "staged, never played")
        phone.play()
        await("Play resumes at the migrated place") { phone.opened.isNotEmpty() }
        assertEquals("2@61000", phone.opened.single())
        await("the queue is not overwritten") { MusicCore.queueOf(rec())!!.entries.map { it.qid } == listOf(1L, 2L) }
    }

    @Test
    fun fix7_away_theCardShowsPaused_andItsToggleStartsHere() {
        val h = hosts(pcUnreachable = true)
        val pcSrc = source("local")
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_OUT, listOf(MusicCore.OUT), MusicCore.Out(MusicCore.PC, t).json()))
        val q = MusicCore.queueJson(tracks().mapIndexed { i, tr -> wm.damage.core.windows.music.QueueEntry(100L + i, tr) }, Mode.QUEUE, "L")
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_QUEUE, listOf(MusicCore.Q), q))
        pcSrc.act(wm.damage.core.app.Draft(MusicCore.A_NOW, listOf(MusicCore.NOW), MusicCore.Now(100L, 60_000, t, MusicCore.PLAYING, 50).json()))
        await("the phone follows the PC playing") { h.phone.state.output == Output.PC && h.phone.state.play == PlayState.PLAYING }
        h.phone.pretendLinkDown()
        await("shown paused while the PC is out of reach") { h.phone.state.play == PlayState.PAUSED }
        h.phone.toggle()
        await("the toggle took the output") { MusicCore.outOf(rec()).host == MusicCore.PHONE && h.phone.opened.isNotEmpty() }
    }

    @Test
    fun fix9_aRelayEndsWithTheConnectionItWentOutOn() {
        val svc = MusicService(Lib())
        val a = object : WinService.Push { override fun send(op: String, args: JsonObject, blob: ByteArray?) {} }
        val b = object : WinService.Push { override fun send(op: String, args: JsonObject, blob: ByteArray?) {} }
        svc.attached(a); svc.attached(b)
        val answers = java.util.Collections.synchronizedList(ArrayList<String?>())
        svc.relay("next", JsonObject(emptyMap())) { w, _ -> answers += w }   // goes out on b, the latest
        svc.detached(b)
        assertEquals(listOf<String?>(NOT_KNOWN), answers.toList(), "ended with b, though a is still attached")
    }

    @Test
    fun fix10_theSleepTimerMovesWithTheMusic() {
        val h = hosts()
        h.phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        h.phone.setSleep(wm.damage.core.windows.music.Sleep.fromChoice("30 min", t))
        await("set") { h.phone.state.sleep.kind == wm.damage.core.windows.music.Sleep.Kind.TIMER }
        val deadline = h.phone.state.sleep.deadlineMs
        h.phone.setOutput(Output.PC)
        await("the PC holds the timer") { h.pc.state.sleep.deadlineMs == deadline }
        await("the phone's ended") { h.phone.cmds.contains("stop") && h.phone.state.sleep.kind == wm.damage.core.windows.music.Sleep.Kind.OFF }
    }

    @Test
    fun fix11_aRestoreOnABoundPlayer_leavesItPlayingAndItsQueue() {
        // on main every link edge's keeper restart called `restore` on the live player and set it stopped while it sounded
        val phone = SimMusicPlayer(Lib(), clock, source = source("phone"), host = MusicCore.PHONE)
        phone.playQueue(tracks(), 0, Mode.QUEUE, "L")
        await("playing") { phone.state.play == PlayState.PLAYING }
        val saved = phone.persist()
        phone.restore(buildJsonObject { put("play", "STOPPED"); put("posMs", 0) })
        phone.restore(saved)
        Thread.sleep(20)
        assertEquals(PlayState.PLAYING, phone.state.play)
        assertEquals(4, phone.state.queue.size)
        assertEquals(1, phone.opened.size, "nothing reopened")
    }

    @Test
    fun theRelay_pushesACmd_answersOnItsDone_andSaysNotKnownWhenTheChannelEnds() {
        val svc = MusicService(Lib())
        val answers = java.util.Collections.synchronizedList(ArrayList<Pair<String?, Boolean>>())
        svc.relay("next", JsonObject(emptyMap())) { w, u -> answers += w to u }
        assertEquals(listOf<Pair<String?, Boolean>>("the phone has not answered" to true), answers.toList(), "no phone attached: refused at once")
        val sent = ArrayList<Pair<String, JsonObject>>()
        val push = object : WinService.Push { override fun send(op: String, args: JsonObject, blob: ByteArray?) { sent += op to args } }
        svc.attached(push)
        svc.relay("next", JsonObject(emptyMap())) { w, u -> answers += w to u }
        val cmd = sent.single()
        assertEquals("cmd", cmd.first)
        val k = (cmd.second["k"] as JsonPrimitive).content.toLong()
        svc.request("cmd.done", buildJsonObject { put("k", k); put("ok", true) })
        assertEquals(null to false, answers.last())
        svc.relay("pause", JsonObject(emptyMap())) { w, u -> answers += w to u }
        svc.detached(push)
        assertEquals(NOT_KNOWN to false, answers.last(), "a relay whose channel ended is not known to have run")
        svc.phoneUp = { false }
        svc.attached(push)
        svc.relay("play", JsonObject(emptyMap())) { w, u -> answers += w to u }
        assertEquals(true, answers.last().second, "the phone's app connection is down: refused at once")
    }

    @Test
    fun theMigration_0_77Shapes_writeOnce() {
        val store = Persistence(dir.resolve("state.json"))
        val stamp = t - 86_400_000L
        store.tryApplyRemote("window.music", buildJsonObject {
            put("notifyTrack", false); put("notifyPc", true); put("defaultMode", "RADIO"); put("lyricsSources", "+netease")
            put("viz", "Bars"); put("vizRate", 12); put("mmPeek", true); put("height", 0)
            put("lyricsOffsets", buildJsonObject { put("auto", 150); put("17", 40) })
            put("stack", buildJsonArray {})
        }, stamp)
        store.tryApplyRemote("window.music.player", buildJsonObject {
            put("engine", buildJsonObject {
                put("index", 1); put("mode", "QUEUE"); put("label", "Pink Floyd"); put("nextQid", 3)
                put("queue", buildJsonArray {
                    add(buildJsonObject { put("qid", 1); put("id", 1); put("title", "One"); put("artist", "A"); put("album", "Al"); put("dur", 200_000) })
                    add(buildJsonObject { put("qid", 2); put("id", 2); put("title", "Two"); put("artist", "A"); put("album", "Al"); put("dur", 200_000) })
                })
            })
            put("posMs", 61_000); put("posAt", stamp); put("play", "PLAYING"); put("volume", 70)
        }, stamp)
        val notes = ArrayList<String>()
        MusicMigration.onHost(host, store) { notes += it }
        val r = rec()
        assertEquals(false, MusicCore.notify(r, MusicCore.NOTIFY_TRACK))
        assertEquals(true, MusicCore.notify(r, MusicCore.NOTIFY_PC))
        assertEquals(Mode.RADIO, MusicCore.defaultMode(r))
        assertEquals("+netease", MusicCore.lyricSources(r))
        assertEquals("Bars", MusicCore.viz(r)); assertEquals(12, MusicCore.vizRate(r))
        assertEquals(true, MusicCore.mm(r, "peek")); assertEquals(null, MusicCore.size(r), "height 0 = Global")
        assertEquals(150, MusicCore.offset(r, MusicCore.OFFSET_PHONE + "Auto"))
        val q = assertNotNull(MusicCore.queueOf(r))
        assertEquals(listOf(1L, 2L), q.entries.map { it.qid }, "the qids kept")
        assertEquals("Pink Floyd", q.label)
        val now = assertNotNull(MusicCore.nowOf(r))
        assertEquals(2L, now.e); assertEquals(61_000L, now.pos); assertEquals(MusicCore.STOPPED, now.state, "never playing")
        assertEquals(MusicCore.PHONE, MusicCore.outOf(r).host)
        assertNull(r.itemNamed(MusicCore.OUT), "out stays absent: the phone plays (answer 1)")
        assertEquals(1, notes.size)
        assertTrue(MusicMigration.MIGRATE.offers(store, host.read(MusicCore.WINDOW).third.items.keys).isEmpty(), "a second start writes nothing")
    }

    @Test
    fun theQueue_randomIds_andAtMostAHundredPlayedBehind() {
        val e = QueueEngine()
        e.set((1..150).map { TrackRef(it, "t$it") }, 0, Mode.QUEUE, "")
        assertEquals(150, e.entries.map { it.qid }.toSet().size)
        assertTrue(e.entries.all { it.qid > 0 })
        repeat(130) { e.next() }
        assertEquals(QueueEngine.KEEP_PLAYED, e.index)
        assertEquals(131, e.current!!.track.id)
        assertEquals(120, e.size)
    }

    /** [url] the stream prefix for every track, or for [only] that track (the others `sim://`). */
    private class Lib(private val url: String = "sim://", private val only: Int? = null) : MusicLibrary {
        val tracks = (1..4).map { TrackMeta(it, "T$it", "A", "Al", 200_000, it, 1, 2000, listOf("rock"), emptyList(), folder = "L") }
        override fun stateLine() = ""
        override fun catalog() = Catalog("c1", 1, tracks)
        override fun refreshCatalog() {}
        override fun search(q: String) = tracks.map { it.ref() }
        override fun ask(request: String) = ResolvedQueue(emptyList(), "empty", request, "none")
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int) = emptyList<TrackRef>()
        override fun randomLibrary(n: Int, exclude: List<Int>) = emptyList<TrackRef>()
        override fun playlists() = emptyList<Playlist>()
        override fun playlistTracks(id: Int) = emptyList<TrackRef>()
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean) = Playlist(9, name)
        override fun renamePlaylist(id: Int, name: String) {}
        override fun deletePlaylist(id: Int) {}
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) {}
        override fun lyrics(trackId: Int) = null
        override fun searchLyrics(trackId: Int, query: String) = emptyList<Lyrics>()
        override fun setLyrics(trackId: Int, choice: Lyrics) {}
        override fun art(trackId: Int, px: Int): ByteArray? = null
        override fun viz(trackId: Int) = null
        override fun ytSearch(q: String) = emptyList<YtResult>()
        override fun ytGrab(id: String) = "j"
        override fun ytStatus(job: String) = YtJob(job)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {}
        override fun streamUrl(trackId: Int, profile: AudioProfile) = if (only == null || only == trackId) "$url$trackId" else "sim://$trackId"
        override fun pretranscode(profile: AudioProfile) = ""
        override fun rescan() = ""
        override fun addListener(l: MusicLibrary.Listener) {}
        override fun removeListener(l: MusicLibrary.Listener) {}
        override fun setFocused(focused: Boolean, paceMs: Long) {}
    }
}
