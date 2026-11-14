package wm.damage.core

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.web.LocalSecret
import wm.damage.core.web.WebAccess

/*
 * Build S1-1, package P6 (`CONTRACT.md` §10.6, §13 (b) "local secret file"): the file that makes the command line the
 * device `local` on loopback — created readable by his user only, in one step, at a start that finds it missing; never
 * rewritten by a start; refused (and said) once another user could read it; never in the log. Temp directories only.
 */
class LocalSecretTest {

    @Test
    fun createdOwnerOnlyAtAStartAndNeverRewritten() {
        val dir = Files.createTempDirectory("damage-secret")
        try {
            val said = ArrayList<String>()
            WebAccess.atProcessStart(dir) { said += it }
            val f = dir.resolve(LocalSecret.FILE)
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)))
            val first = LocalSecret.read(dir)
            assertNotNull(first)
            assertTrue(first.length >= 43, "32 random bytes: ${first.length} characters")
            assertTrue(said.any { "was created" in it }, "the start says it: $said")
            WebAccess.atProcessStart(dir) { said += it }
            assertEquals(first, LocalSecret.read(dir), "a later start keeps it")
            assertTrue(said.last { "local secret" in it }.contains("in place"))
            assertTrue(said.none { first in it }, "no start line carries it")
            assertTrue(recentLog().none { first in it }, "no log line carries it")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aFileOtherUsersCouldReadIsRefusedAndSaid() {
        val dir = Files.createTempDirectory("damage-secret")
        try {
            LocalSecret.ensure(dir)
            val f = dir.resolve(LocalSecret.FILE)
            val secret = LocalSecret.read(dir)!!
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"))
            assertNull(LocalSecret.read(dir), "readable by others: refused")
            val line = LocalSecret.ensure(dir)
            assertTrue("NOT used" in line && "rw-r--r--" in line, "the start says why: $line")
            assertEquals(secret, Files.readString(f).trim(), "a start never rewrites it")
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw----r--"))
            assertNull(LocalSecret.read(dir), "readable by others alone: refused")
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"))
            assertEquals(secret, LocalSecret.read(dir), "back to his user only: used")
            assertTrue(recentLog().none { secret in it }, "no log line carries it")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aMissingOrShortFileGivesNoSecret() {
        val dir = Files.createTempDirectory("damage-secret")
        try {
            assertNull(LocalSecret.read(dir), "missing")
            val f = dir.resolve(LocalSecret.FILE)
            Files.writeString(f, "short\n")
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"))
            assertNull(LocalSecret.read(dir), "too short to be one")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
