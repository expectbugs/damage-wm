package wm.damage.core

import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.CoreExecutor
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.BlobSource
import wm.damage.core.web.Bundle
import wm.damage.core.web.ClassPathVendor
import wm.damage.core.web.Devices
import wm.damage.core.web.VendorSource
import wm.damage.core.web.WebServer

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B12: the terminal library's route — exactly three names, their types, the bundle's headers, no
 * device needed; any other name 404, and a host without the library (the phone) 404 with the reason — and the class
 * path reader over a made-up WebJar layout in a temp directory (the real jar is the desktop module's, checked there).
 */
class VendorRouteTest {
    private val dir = Files.createTempDirectory("damage-vendor")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-vendor")).also { it.open() }
    private val net = AppNet(host)
    private val servers = ArrayList<WebServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
        net.close()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun server(vendor: VendorSource) = WebServer(0, ReachGate(Side.PC), Access(listOf("127.0.0.1")),
        Devices(dir.resolve(Devices.FILE)).load(), net, Bundle(mapOf("index.html" to "<p>made-up</p>".toByteArray())),
        BlobSource { null }, bindAddress = InetAddress.getLoopbackAddress(), vendor = vendor).also { it.start(); servers += it }

    /** One GET without a cookie: (status, headers lowercased, body). */
    private fun get(port: Int, path: String): Triple<Int, Map<String, String>, String> {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
            val inp = DataInputStream(s.getInputStream())
            val all = inp.readBytes().toString(Charsets.UTF_8)
            val head = all.substringBefore("\r\n\r\n").lines()
            val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Triple(head[0].split(' ')[1].toInt(), headers, all.substringAfter("\r\n\r\n"))
        }
    }

    @Test
    fun theThreeFilesAreServedAsBundleFiles_anythingElseIs404() {
        val files = mapOf("xterm.mjs" to "export{T as Terminal};", "xterm.css" to ".xterm{}", "LICENSE" to "made-up MIT text")
        val port = server { files[it]?.toByteArray() }.boundPort
        val (code, h, body) = get(port, "/vendor/xterm/xterm.mjs")
        assertEquals(200, code)
        assertEquals("export{T as Terminal};", body)
        assertEquals("text/javascript; charset=utf-8", h["content-type"])
        assertTrue(h["content-security-policy"]!!.contains("script-src 'self'"), "the bundle's policy: $h")
        assertEquals("nosniff", h["x-content-type-options"])
        assertEquals("text/css; charset=utf-8", get(port, "/vendor/xterm/xterm.css").second["content-type"])
        assertEquals("text/plain; charset=utf-8", get(port, "/vendor/xterm/LICENSE").second["content-type"])
        for (other in listOf("/vendor/xterm/xterm.mjs.map", "/vendor/xterm/package.json", "/vendor/xterm/../index.html", "/vendor/xterm/"))
            assertEquals(404, get(port, other).first, other)
        val phone = server { null }.boundPort
        val (c2, _, b2) = get(phone, "/vendor/xterm/xterm.mjs")
        assertEquals(404, c2)
        assertEquals("this host serves no terminal library", b2)
    }

    @Test
    fun theClassPathReaderTakesTheVersionFromTheWebJarsOwnPom() {
        val root = Files.createDirectories(dir.resolve("cp"))
        val pom = root.resolve(ClassPathVendor.POM)
        Files.createDirectories(pom.parent)
        Files.writeString(pom, "version=9.9.9\ngroupId=org.webjars.npm\nartifactId=xterm__xterm\n")
        val lib = root.resolve("META-INF/resources/webjars/xterm__xterm/9.9.9")
        Files.createDirectories(lib.resolve("lib")); Files.createDirectories(lib.resolve("css"))
        Files.writeString(lib.resolve("lib/xterm.mjs"), "made-up module")
        Files.writeString(lib.resolve("css/xterm.css"), "made-up sheet")
        val loader = java.net.URLClassLoader(arrayOf(root.toUri().toURL()), null)
        val v = ClassPathVendor(loader)
        assertEquals("made-up module", v.file("xterm.mjs")?.toString(Charsets.UTF_8))
        assertEquals("made-up sheet", v.file("xterm.css")?.toString(Charsets.UTF_8))
        assertNull(v.file("LICENSE"), "a file the jar lacks is said and answered 404")
        assertNull(ClassPathVendor(java.net.URLClassLoader(arrayOf(), null)).file("xterm.mjs"), "no library: none")
        loader.close()
    }
}
