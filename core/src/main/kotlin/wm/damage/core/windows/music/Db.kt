package wm.damage.core.windows.music

/**
 * The SQL seam (`MUSIC.md` §9.1): core holds every query the music system
 * runs against Postgres `g2cc` — as text, against this small interface — and
 * the DESKTOP supplies the driver (`PgDb`: pgjdbc over the Unix socket via
 * junixsocket, peer auth, no password). Core stays Android-clean: the APK
 * never carries a JDBC driver, and the SQL layer is testable over a fake.
 *
 * Parameters are positional `?` placeholders (JDBC style). Typed wrappers
 * carry what plain Kotlin types cannot: [TextArr] binds a `text[]`,
 * [IntArr] an `int4[]`, [Jsonb] a `jsonb` value. Every failure throws —
 * a down database is a loud exception the caller renders (store rules).
 */
interface Db {
    fun query(sql: String, vararg args: Any?): List<Row>
    fun exec(sql: String, vararg args: Any?): Int
    /** One transaction; [block] runs on the same connection, committed on a
     *  normal return, rolled back on a throw. */
    fun <T> tx(block: (Db) -> T): T
    fun close() {}

    class TextArr(val v: List<String>)
    class IntArr(val v: List<Int>)
    class Jsonb(val json: String)

    /** One result row, keyed by lower-case column label. Accessors coerce:
     *  nulls read as ""/0/false/empty — the wire lies about types, so cast at
     *  the boundary (the global rule). */
    class Row(private val m: Map<String, Any?>) {
        fun raw(k: String): Any? = m[k.lowercase()]
        fun has(k: String): Boolean = m.containsKey(k.lowercase())
        fun str(k: String): String = raw(k)?.toString() ?: ""
        fun strOrNull(k: String): String? = raw(k)?.toString()
        fun int(k: String): Int = when (val v = raw(k)) {
            null -> 0
            is Number -> v.toInt()
            else -> v.toString().toDoubleOrNull()?.toInt() ?: 0
        }
        fun long(k: String): Long = when (val v = raw(k)) {
            null -> 0L
            is Number -> v.toLong()
            else -> v.toString().toDoubleOrNull()?.toLong() ?: 0L
        }
        fun double(k: String): Double = when (val v = raw(k)) {
            null -> 0.0
            is Number -> v.toDouble()
            else -> v.toString().toDoubleOrNull() ?: 0.0
        }
        fun bool(k: String): Boolean = when (val v = raw(k)) {
            null -> false
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().equals("t", true) || v.toString().equals("true", true)
        }
        /** A `text[]` column (the driver hands it over as a List already). */
        @Suppress("UNCHECKED_CAST")
        fun list(k: String): List<String> = when (val v = raw(k)) {
            null -> emptyList()
            is List<*> -> v.mapNotNull { it?.toString() }
            is Array<*> -> v.mapNotNull { it?.toString() }
            else -> listOf(v.toString())
        }
        override fun toString(): String = m.toString()
    }
}
