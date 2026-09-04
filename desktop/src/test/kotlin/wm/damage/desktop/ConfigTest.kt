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
}
