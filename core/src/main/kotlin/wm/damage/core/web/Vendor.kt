package wm.damage.core.web

import java.util.Properties
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B12: the terminal emulator a desk browser's Tmux page loads — `@xterm/xterm` (MIT; read from the
 * jar's own `LICENSE` and `package.json`), served from the build's dependency `org.webjars.npm:xterm__xterm` (one pin
 * in the version catalog, run time only, in the desktop module), never a copy in the tree. Core reads it through
 * [VendorSource] beside `FontSource`; the PC's two builders give [ClassPathVendor]; the phone gives none (404).
 */

/** The terminal library's files by their route name ([WebServer.VENDOR_FILES]), or null when this host has none. */
fun interface VendorSource {
    fun file(name: String): ByteArray?
}

/**
 * The library from [loader]'s class path, its version read from the WebJar's own `pom.properties` (the catalog's pin
 * drives it; no second constant). Read once, at the first ask; a file the class path lacks is said once and answered
 * 404.
 */
class ClassPathVendor(private val loader: ClassLoader) : VendorSource {
    private val files: Map<String, ByteArray> by lazy { load() }

    override fun file(name: String): ByteArray? = files[name]

    private fun load(): Map<String, ByteArray> {
        val props = loader.getResourceAsStream(POM)?.use { s -> Properties().apply { load(s) } }
        val v = props?.getProperty("version")
        if (v.isNullOrBlank()) {
            Log.w(TAG, "no terminal library on this class path ($POM missing): a page's terminal shows the pictures instead")
            return emptyMap()
        }
        val root = "META-INF/resources/webjars/xterm__xterm/$v/"
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, path) in mapOf("xterm.mjs" to "lib/xterm.mjs", "xterm.css" to "css/xterm.css", "LICENSE" to "LICENSE")) {
            val b = loader.getResourceAsStream(root + path)?.use { it.readBytes() }
            if (b == null) Log.w(TAG, "the terminal library $v lacks $path on this class path") else out[name] = b
        }
        Log.i(TAG, "terminal library @xterm/xterm $v from the class path: ${out.keys.joinToString()}")
        return out
    }

    companion object {
        private const val TAG = "web"
        const val POM = "META-INF/maven/org.webjars.npm/xterm__xterm/pom.properties"
    }
}
