package wm.damage.core.app

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.5; `CONTRACT.md` §2.4's register row): the words of a register op that lost,
 * for a glasses presenter to say through today's answer notice — "not applied — Notify · done: you off (phone 13:50) ·
 * standing on, set in a browser 14:02" — once per (item, standing write): a loss already said for the same standing
 * write is not said again (a resend, a second view of the same answer). Reader keeps its own words (S1-1).
 */
class LossWords(private val zone: ZoneId = ZoneId.systemDefault()) {
    /** The (item, by, at) of the standing writes already said; the last [KEEP]. */
    private val said = LinkedHashSet<Triple<String, String, Long>>()

    /**
     * The line for [loss] of the register row [label], or null when this standing write was said already. [mineWhere]
     * names where the losing value was set ("phone", "glasses"), [mineAt] when (PC or device clock, ms); [words] gives
     * a value in the row's own words ("on", "off", "480"). Thread-safe.
     */
    fun register(label: String, loss: Loss, mineWhere: String, mineAt: Long, words: (JsonElement) -> String = ::plain): String? {
        val key = Triple(loss.item, loss.by, loss.at)
        synchronized(said) {
            if (!said.add(key)) return null
            while (said.size > KEEP) said.remove(said.first())
        }
        val standing = if (loss.gone) "removed" else words(loss.standing)
        return "not applied — $label: you ${words(loss.mine)} ($mineWhere ${hhmm(mineAt)}) · standing $standing, " +
            "set ${where(loss.by)} ${hhmm(loss.at)}"
    }

    fun hhmm(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }

    companion object {
        const val KEEP = 1_000

        /** Where a device id's write was made, in words (a browser's id is `web-` and six hex digits, `Devices`). */
        fun where(device: String): String = when (device) {
            Peer.LOCAL.device -> "on the PC"
            Peer.PHONE.device -> "on the phone"
            Migrate.DEVICE -> "by an earlier build"
            "" -> "on another device"
            else -> if (device.startsWith("web-")) "in a browser" else "on $device"
        }

        /** A value as text: a string's own text, `true`/`false` as on/off, nothing as "none". */
        fun plain(v: JsonElement): String = when {
            v is JsonNull -> "none"
            v is JsonPrimitive && v.isString -> v.content
            v is JsonPrimitive && v.contentOrNull == "true" -> "on"
            v is JsonPrimitive && v.contentOrNull == "false" -> "off"
            else -> v.toString()
        }
    }
}
