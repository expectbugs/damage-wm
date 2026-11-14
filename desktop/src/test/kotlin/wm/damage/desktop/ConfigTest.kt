package wm.damage.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §28 #4 — an UNREADABLE `~/.damage/config.json` (one stray comma in a hand
 * edit) was replaced by defaults on the next start: the fallback `Config()`
 * got a fresh token, differed from what was read, and was stored. Tracker
 * credentials, tmux hosts and the phone's token went with it. The file is
 * left exactly as it is now; the defaults run for that start only.
 */
class ConfigTest {

    private fun <T> withHome(home: java.nio.file.Path, block: () -> T): T {
        val old = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try { return block() } finally { System.setProperty("user.home", old) }
    }

    @Test
    fun anUnreadableConfigIsNeverOverwritten() {
        val home = Files.createTempDirectory("damage-config")
        val p = home.resolve(".damage").resolve("config.json")
        Files.createDirectories(p.parent)
        val broken = "{ \"torrentleechUser\": \"adam\", \"tmuxHosts\": [ { \"name\": \"slappy\", }, ] "
        Files.writeString(p, broken)
        val cfg = withHome(home) { Config.load() }
        assertEquals(broken, Files.readString(p), "the unreadable file must be left for the person to fix")
        assertTrue(cfg.token.isNotEmpty(), "the in-memory defaults still carry a token for this start")
        assertEquals("", cfg.torrentleechUser, "defaults run for this start only")
    }

    @Test
    fun aMissingConfigIsCreatedAndATokenlessOneGetsAToken() {
        val home = Files.createTempDirectory("damage-config2")
        val p = home.resolve(".damage").resolve("config.json")
        val fresh = withHome(home) { Config.load() }
        assertTrue(Files.exists(p) && fresh.token.isNotEmpty(), "a fresh box gets a written config with a token")
        Files.writeString(p, "{ \"token\": \"\", \"torrentleechUser\": \"adam\" }")
        val filled = withHome(home) { Config.load() }
        assertTrue(filled.token.isNotEmpty() && filled.torrentleechUser == "adam",
            "a readable file without a token is completed, keeping what it had")
        assertTrue(Files.readString(p).contains("\"adam\""), "the completed file keeps its other fields")
    }

    /** Build W (R6 T16 as amended by the lead's decisions A.1, A.5, A.6, B.4): the new keys' defaults; a file
     *  written before them loads unchanged; there is NO `haToken` key and no rule list in the config (the token
     *  lives in the file `haTokenFile` names, the rules in their own file); `reachOpen` is off unless set. */
    @Test
    fun theWatcherKeysDefaultsAndNoTokenOrRuleListInTheConfig() {
        val d = Config()
        assertEquals(listOf("", "", "", 10, 5), listOf(d.haUrl, d.haTokenFile, d.haRulesFile, d.haPollSec, d.haSilentMin))
        assertEquals(listOf("", 14, "03:30"), listOf(d.backupDir, d.backupKeep, d.backupAt))
        assertEquals(false, d.reachOpen, "the reach gate is ON unless the config turns it off")
        assertEquals(java.nio.file.Path.of(d.dataDir, "bastion-rules.json"), d.rulesPath())
        assertEquals(java.nio.file.Path.of("/x/r.json"), d.copy(haRulesFile = "/x/r.json").rulesPath())
        assertEquals(java.nio.file.Path.of(d.dataDir, "feed"), d.feedDir())

        val names = (0 until Config.serializer().descriptor.elementsCount).map { Config.serializer().descriptor.getElementName(it) }
        assertTrue("haToken" !in names, "no haToken key: the token is read from haTokenFile")
        assertEquals(listOf("haRulesFile"), names.filter { "rule" in it.lowercase() }, "no rule list in the config")
        assertEquals(listOf("token", "setupToken"), names.filter { it.lowercase().endsWith("token") },
            "no new token-valued key")

        val home = Files.createTempDirectory("damage-config3")
        val p = home.resolve(".damage").resolve("config.json")
        Files.createDirectories(p.parent)
        val old = "{ \"token\": \"t0\", \"torrentleechUser\": \"adam\" }"
        Files.writeString(p, old)
        val loaded = withHome(home) { Config.load() }
        assertEquals(listOf("adam", "", false, 10), listOf(loaded.torrentleechUser, loaded.haUrl, loaded.reachOpen, loaded.haPollSec),
            "a file without the new keys loads on their defaults")
        assertEquals(old, Files.readString(p), "and is not rewritten")
    }

    /** The nightly copy's factory: off without `backupDir`, said; a `backupAt` it cannot read is off, said, and
     *  raises the copy's alert (a copy that never runs is never silent). */
    @Test
    fun theNightlyCopyFactorySaysWhyItIsOff() {
        val dir = Files.createTempDirectory("damage-config4")
        val store = wm.damage.core.bastion.AlertStore(dir.resolve("alerts.json"))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val base = Config(dataDir = dir.toString())
        assertEquals(null, base.nightlyCopy(store, scope))
        assertEquals(null, base.copy(backupDir = dir.toString(), backupAt = "3h30").nightlyCopy(store, scope))
        val alert = store.table().single { it.id == wm.damage.core.bastion.BastionWatcher.COPY }
        assertTrue("backupAt" in alert.text, alert.text)
        assertTrue(base.copy(backupDir = dir.toString()).nightlyCopy(store, scope) != null)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
