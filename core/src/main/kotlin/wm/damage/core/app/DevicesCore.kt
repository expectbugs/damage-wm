package wm.damage.core.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import wm.damage.core.web.Devices

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §10.3, §10.8; the spec's §6 "The first approval"): the device table as a
 * PC-served scope of the `app` lane — its description (the approved devices and the waiting pages, never a hash) and
 * its actions at level full, never queued (`(conn, k)`): `approve {code, level}`, `deny {code}`, `revoke {id}`,
 * `rename {id, name}`. The command line ([DeviceCli]) and, from P7, the desk page's devices view are its clients; the
 * level is checked by [AppNet] at apply time, before [act].
 */
class DevicesCore(val table: Devices) {
    /** The description: rows (no hash), waiting pages, the actions and their level. */
    fun describe(): JsonObject = buildJsonObject {
        put("id", SCOPE)
        put("name", "Devices")
        put("min", Levels.FULL)
        put("rows", buildJsonArray {
            for (r in table.rows()) if (!r.revoked) add(buildJsonObject {
                put("id", r.id); put("name", r.name); put("kind", r.kind); put("level", r.level)
                r.tailnetName?.let { put("tailnetName", it) }
                r.address?.let { put("address", it) }
                put("approvedAt", r.approvedAt); put("approvedBy", r.approvedBy); put("lastSeen", r.lastSeen)
            })
        })
        put("pending", buildJsonArray {
            for (p in table.pending()) add(buildJsonObject {
                put("code", p.code); put("address", p.address)
                p.tailnetName?.let { put("tailnetName", it) }
                put("name", p.name); put("kind", p.kind); put("since", p.since)
                p.replaces?.let { put("replaces", it) }
                p.approvedLevel?.let { put("approved", it) }
            })
        })
        put("actions", buildJsonArray {
            for (a in listOf(APPROVE, DENY, REVOKE, RENAME)) add(buildJsonObject {
                put("id", a); put("kind", if (a == REVOKE) "i" else "r"); put("level", Levels.FULL)
            })
        })
    }

    /** One action by [by] (the connection's device); the caller has checked the level. */
    fun act(o: Op, by: String): Answer {
        fun p(k: String): String? = (o.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val why = when (o.action) {
            APPROVE -> {
                val code = p("code") ?: return missing("code")
                val level = p("level") ?: return missing("level")
                table.approve(code, level, by)
            }
            DENY -> table.deny(p("code") ?: return missing("code"), by)
            REVOKE -> table.revoke(p("id") ?: return missing("id"), by)
            RENAME -> {
                val id = p("id") ?: return missing("id")
                table.rename(id, p("name") ?: return missing("name"))
            }
            else -> return Answer.Refused("the device table has no action ${o.action}", Answer.UNKNOWN)
        }
        return if (why == null) Answer.Seen() else Answer.Refused(why, Answer.INVALID)
    }

    private fun missing(k: String) = Answer.Refused("the action needs '$k'", Answer.INVALID)

    companion object {
        /** The scope and the window name on the lane. */
        const val SCOPE = "devices"
        const val APPROVE = "approve"
        const val DENY = "deny"
        const val REVOKE = "revoke"
        const val RENAME = "rename"

        private val HHMM = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        /** A time as the command line shows it. */
        fun at(ms: Long): String = if (ms <= 0) "never" else HHMM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}
