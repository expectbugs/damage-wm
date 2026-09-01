package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import wm.damage.core.util.Log

/**
 * Appends a peer's forwarded log lines to a file the PC user can read directly
 * (HANDOFF.md §19.4 follow-up: the phone's logs on the PC with no adb). Each
 * line gets a PC receive-time prefix; the phone already prefixed its own
 * timestamp/tag, so both clocks are visible. Synchronized append, flushed per
 * line — a diagnostic tail must never sit in a buffer. Rotated once past a
 * size cap so a long-running host cannot grow it without bound.
 */
class DeviceLogFile(private val file: Path) {
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            try {
                file.parent?.let { Files.createDirectories(it) }
                if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                    Files.move(file, file.resolveSibling(file.fileName.toString() + ".1"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                Files.writeString(file, "${LocalTime.now().format(stamp)}  $line\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (e: Exception) {
                Log.w("devlog", "could not append device log: ${e.message}")
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 4L shl 20
    }
}
