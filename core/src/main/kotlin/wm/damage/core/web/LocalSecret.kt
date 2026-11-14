package wm.damage.core.web

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §10.6, §13 (b) "local secret file"): the file that lets the `damage` command
 * line (and, from S2, the companion) act on loopback as the device `local`, level full. Loopback alone is not trusted;
 * the file keeps other users of the machine out and nothing more.
 *
 * - Written by the service at a start that finds it missing ([ensure]), created readable and writable by his user
 *   only IN ONE STEP (the mode is given to the create call, never set after), never rewritten by the service.
 * - Read once per connection ([read]); a file another user could read is refused and said, never used, so a mode a
 *   copy or an editor widened cannot quietly hand the device `local` to someone else.
 * - Its contents never reach a log line, a journal note or an exception's text.
 */
object LocalSecret {
    const val FILE = "local.secret"
    /** 32 random bytes, base64url without padding: 43 characters. */
    private const val BYTES = 32
    private const val MIN_CHARS = 43

    private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /**
     * At a process start (only through [WebAccess.atProcessStart]): creates the file when it is missing and returns
     * the line the start says (never the secret). A file already there is kept as it is, whatever it holds; a wide
     * mode is said here and refused at every [read].
     */
    fun ensure(dir: Path, random: SecureRandom = SecureRandom()): String {
        val f = dir.resolve(FILE)
        if (Files.exists(f)) {
            val why = unusable(f)
            return if (why == null) "the local secret ($FILE) is in place"
            else "the local secret ($FILE) is NOT used: $why".also { Log.e(TAG, it) }
        }
        val b = ByteArray(BYTES).also { random.nextBytes(it) }
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(b) + "\n"
        Files.createDirectories(dir)
        try {
            // CREATE_NEW with the mode in the same call: the file never exists with a wider mode
            FileChannel.open(f, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_ONLY)).use { ch ->
                val buf = ByteBuffer.wrap(text.toByteArray(Charsets.US_ASCII))
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            return "the local secret ($FILE) appeared while this start wrote it; kept as it is"
        }
        // the process umask only removes bits; a mode wider than asked would be a filesystem without POSIX modes
        unusable(f)?.let { return "the local secret ($FILE) was written but is NOT used: $it".also { m -> Log.e(TAG, m) } }
        return "the local secret ($FILE) was created, readable by this user only"
    }

    /** The secret, or null: missing, readable by another user, or too short (each said, never the contents). */
    fun read(dir: Path): String? {
        val f = dir.resolve(FILE)
        val why = try {
            unusable(f)
        } catch (e: NoSuchFileException) {
            return null
        }
        if (why != null) {
            Log.e(TAG, "the local secret is refused: $why")
            return null
        }
        val s = try {
            String(Files.readAllBytes(f), Charsets.US_ASCII).trim()
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: Exception) {
            Log.e(TAG, "the local secret could not be read (${e::class.simpleName})")
            return null
        }
        if (s.length < MIN_CHARS) {
            Log.e(TAG, "the local secret is refused: $FILE holds ${s.length} characters, fewer than $MIN_CHARS")
            return null
        }
        return s
    }

    /** Why [f] cannot be used, or null; throws [NoSuchFileException] when it is missing. */
    private fun unusable(f: Path): String? {
        val mode = try {
            Files.getPosixFilePermissions(f)
        } catch (e: UnsupportedOperationException) {
            return "this filesystem keeps no POSIX modes, so the file cannot be kept to this user"
        }
        if (!Files.isRegularFile(f)) return "$FILE is not a regular file"
        val wide = mode.filter { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }
        if (wide.isNotEmpty()) return "$FILE has the mode ${PosixFilePermissions.toString(mode)}; other users could read it " +
            "(chmod 600 it, or delete it and restart the service to have a new one written)"
        return null
    }

    private const val TAG = "local-secret"
}
