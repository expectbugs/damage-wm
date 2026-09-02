package wm.damage.desktop

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import wm.damage.core.windows.music.Db

/**
 * The music system's Postgres driver (`MUSIC.md` §9.1): pgjdbc over the
 * Unix socket via junixsocket's socket factory — peer auth as the OS user,
 * no password, exactly how G2CC's pool and the enrichment runner connect.
 * One connection, one lock: the host is one process and the SQL is small;
 * a lost connection is reopened on the next call (the failure itself is
 * thrown to the caller — never hidden).
 */
class PgDb(private val database: String, private val socketDir: String) : Db {
    private val lock = ReentrantLock()
    @Volatile private var conn: Connection? = null

    private fun open(): Connection {
        conn?.let { c -> if (!c.isClosed) return c }
        val url = "jdbc:postgresql://localhost/$database?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg" +
            "&socketFactoryArg=$socketDir/.s.PGSQL.5432&sslmode=disable"
        val c = DriverManager.getConnection(url, System.getProperty("user.name"), null)
        c.autoCommit = true
        conn = c
        return c
    }

    private fun bind(st: PreparedStatement, c: Connection, args: Array<out Any?>) {
        for ((i, a) in args.withIndex()) {
            val idx = i + 1
            when (a) {
                null -> st.setNull(idx, java.sql.Types.NULL)
                is String -> st.setString(idx, a)
                is Int -> st.setInt(idx, a)
                is Long -> st.setLong(idx, a)
                is Boolean -> st.setBoolean(idx, a)
                is Double -> st.setDouble(idx, a)
                is Float -> st.setFloat(idx, a)
                is Db.TextArr -> st.setArray(idx, c.createArrayOf("text", a.v.toTypedArray()))
                is Db.IntArr -> st.setArray(idx, c.createArrayOf("int4", a.v.toTypedArray()))
                is Db.Jsonb -> st.setObject(idx, org.postgresql.util.PGobject().apply { type = "jsonb"; value = a.json })
                else -> throw IllegalArgumentException("unbindable argument ${a::class.simpleName}")
            }
        }
    }

    private fun rows(rs: ResultSet): List<Db.Row> {
        val md = rs.metaData
        val n = md.columnCount
        val labels = (1..n).map { md.getColumnLabel(it).lowercase() }
        val out = ArrayList<Db.Row>()
        while (rs.next()) {
            val m = HashMap<String, Any?>(n * 2)
            for (i in 1..n) {
                val v = rs.getObject(i)
                m[labels[i - 1]] = when (v) {
                    is java.sql.Array -> (v.array as? Array<*>)?.map { it?.toString() } ?: emptyList<String>()
                    is java.sql.Timestamp -> v.time
                    is java.math.BigDecimal -> v.toDouble()
                    is org.postgresql.util.PGobject -> v.value
                    else -> v
                }
            }
            out.add(Db.Row(m))
        }
        return out
    }

    private fun <T> withConn(block: (Connection) -> T): T = lock.withLock {
        val c = open()
        try {
            block(c)
        } catch (e: java.sql.SQLException) {
            // a broken link is dropped so the next call reopens; the error
            // itself still reaches the caller (loud by law)
            if (e.sqlState?.startsWith("08") == true) { try { c.close() } catch (e2: Exception) { /* closing */ }; conn = null }
            throw e
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Db.Row> = withConn { c ->
        c.prepareStatement(sql).use { st -> bind(st, c, args); st.executeQuery().use { rows(it) } }
    }

    override fun exec(sql: String, vararg args: Any?): Int = withConn { c ->
        c.prepareStatement(sql).use { st -> bind(st, c, args); st.executeUpdate() }
    }

    override fun <T> tx(block: (Db) -> T): T = withConn { c ->
        c.autoCommit = false
        try {
            val r = block(this)          // the lock is reentrant: the block's calls reuse this connection
            c.commit()
            r
        } catch (e: Exception) {
            try { c.rollback() } catch (e2: Exception) { /* the throw below carries the cause */ }
            throw e
        } finally {
            try { c.autoCommit = true } catch (e: Exception) { /* a closed link reopens next time */ }
        }
    }

    override fun close() { lock.withLock { try { conn?.close() } catch (e: Exception) { /* closing */ }; conn = null } }
}
