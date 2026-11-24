package wm.damage.desktop.music

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The player program's stand-in as a child process (S1-4 §4): a test JVM main that [MpvProcess] starts in place of
 * mpv. It takes mpv's command line, serves [FakeMpvServer] at `--input-ipc-server=`, and ends when its stdin closes
 * (mpv's `--input-ipc-client=fd://0` tie), writing `fake-stdin-eof` beside its socket first. Test commands over the
 * socket: `damage-fake-hang` (answers nothing from then on), `damage-fake-exit <code>` (exits at once with it).
 * `--fake-no-socket` among its arguments: it never opens the socket. Plays nothing, opens no device.
 */
object FakeMpvMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sock = args.firstOrNull { it.startsWith("--input-ipc-server=") }?.substringAfter('=')
        if (sock == null || "--input-ipc-client=fd://0" !in args) {
            System.err.println("FakeMpvMain: no --input-ipc-server= or --input-ipc-client=fd://0 in ${args.toList()}")
            exitProcess(2)
        }
        if ("--fake-no-socket" !in args) {
            val server = FakeMpvServer(Path.of(sock))
            server.intercept = { cmd ->
                when ((cmd[0] as JsonPrimitive).contentOrNull) {
                    "damage-fake-hang" -> { server.answering = false; true }
                    "damage-fake-exit" -> exitProcess((cmd[1] as JsonPrimitive).intOrNull ?: 1)
                    else -> false
                }
            }
        }
        val stdin = System.`in`
        while (stdin.read() >= 0) { /* mpv reads commands here; the fake only waits for the end */ }
        Files.writeString(Path.of(sock).resolveSibling("fake-stdin-eof"), "")
        exitProcess(0)
    }
}
