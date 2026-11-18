package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import wm.damage.core.web.Bundle
import wm.damage.core.web.WebServer

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P7 (the spec's §2 P7 "Tests" and §7's `S1-bundle-inline-script`; CONTRACT.md §9.5): the web
 * client as the PC serves it — every file a page references is in the bundle and nothing loads from outside the
 * machine; no inline script, no markup written from text; the policy itself allows no inline script (inline styles
 * it allows, L1 AC28); the bundle's hash is stable and moves with any byte.
 */
class BundleTest {
    private val bundle = Bundle.fromResources()
    private fun text(name: String) = String(bundle.files[name] ?: error("$name is not in the bundle"), Charsets.UTF_8)

    private val listed: List<String> = String(Bundle::class.java.getResourceAsStream("/web/${Bundle.LIST}")!!.readBytes())
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun everyFileAPageReferencesIsInTheBundleAndNothingComesFromOutside() {
        assertEquals(listed.toSet(), bundle.files.keys, "every listed file is on the class path")
        val html = text(Bundle.INDEX)
        val refs = Regex("""(?:src|href)\s*=\s*"([^"]*)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(refs.isNotEmpty())
        for (r in refs) {
            assertTrue(r.startsWith("/") && !r.startsWith("//"), "the page references only the PC's own files: $r")
            assertTrue(r.removePrefix("/") in bundle.files, "index.html references $r, which the bundle lacks")
        }
        val css = bundle.files.keys.filter { it.endsWith(".css") }
        for (c in css) {
            assertFalse("@import" in text(c), "$c imports nothing")
            for (u in Regex("""url\(\s*['"]?([^'")]+)""").findAll(text(c)).map { it.groupValues[1] }) {
                val name = u.removePrefix(WebServer.FONT_PREFIX)
                assertTrue(u.startsWith(WebServer.FONT_PREFIX) && name in WebServer.FONTS || u.removePrefix("/") in bundle.files,
                    "$c loads $u, which is neither one of the PC's faces nor a bundle file")
            }
        }
        val modules = bundle.files.keys.filter { it.endsWith(".mjs") }
        assertTrue("js/app.mjs" in modules)
        val imports = Regex("""(?:^|\s)(?:import|export)\s[^'"]*?from\s*['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE)
        for (m in modules) {
            for (hit in imports.findAll(text(m))) {
                val spec = hit.groupValues[1].ifEmpty { hit.groupValues[2] }
                // S1-3 B12: the terminal library the PC serves beside the bundle, by its one route
                if (spec == WebServer.VENDOR_PREFIX + "xterm.mjs") continue
                assertTrue(spec.startsWith("./") || spec.startsWith("../"), "$m imports $spec: only the bundle's own modules")
                val resolved = java.nio.file.Path.of(m).resolveSibling(spec).normalize().toString().replace('\\', '/')
                assertTrue(resolved in bundle.files, "$m imports $spec ($resolved), which the bundle lacks")
            }
            val src = text(m)
            for (bad in listOf(Regex("""(?:fetch|import)\(\s*['"`]https?:"""), Regex("""new\s+WebSocket\(\s*['"`]wss?://[^$]"""))) {
                assertFalse(bad.containsMatchIn(src), "$m reaches outside the machine: ${bad.find(src)?.value}")
            }
        }
    }

    @Test
    fun noInlineScriptNoMarkupFromText() {
        val html = text(Bundle.INDEX)
        val scripts = Regex("""<script\b([^>]*)>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        assertTrue(scripts.isNotEmpty(), "the page loads its module")
        for (s in scripts) {
            assertTrue(Regex("""\bsrc\s*=""").containsMatchIn(s.groupValues[1]), "a script with no src: ${s.value}")
            assertTrue(s.groupValues[2].isBlank(), "a script with a body: ${s.value}")
        }
        assertFalse(Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "an inline event handler in index.html")
        assertFalse("javascript:" in html.lowercase())
        for (m in bundle.files.keys.filter { it.endsWith(".mjs") }) {
            val src = text(m)
            for (bad in listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
                "javascript:")) {
                assertFalse(bad in src, "$m uses $bad: text goes in as text")
            }
        }
    }

    /** L1 AC28: inline styles are allowed (a terminal emulator sets them); scripts stay the bundle's own. */
    @Test
    fun thePolicyAllowsNoInlineScript() {
        val csp = Bundle.csp("pc.example:7405")
        val parts = csp.split(';').map { it.trim() }.associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
        assertEquals("'self'", parts["script-src"], csp)
        assertEquals("'self' 'unsafe-inline'", parts["style-src"], csp)
        assertEquals("'none'", parts["default-src"], csp)
        assertEquals("'self' ws://pc.example:7405", parts["connect-src"], csp)
        assertFalse("unsafe-eval" in csp || "*" in csp, csp)
        assertEquals(listOf("style-src"), parts.filterValues { "unsafe-inline" in it }.keys.toList(), csp)
    }

    /** L1 R2 A6 (RP NOTE 1): the pages speak the lane version the PC does; a mismatch would have every page reload
     *  itself every 2 s for ever (refused `proto`, then the same bundle again). */
    @Test
    fun thePagesProtoIsTheLanes() {
        assertTrue("export const PROTO = ${wm.damage.core.app.AppWire.PROTO};" in text("js/proto.mjs"), "js/proto.mjs's PROTO")
    }

    @Test
    fun theHashIsStableAndMovesWithAnyByteOrName() {
        assertEquals(bundle.hash, Bundle.fromResources().hash, "the same files, the same hash")
        val one = bundle.files.toMutableMap()
        val (name, bytes) = one.entries.first { it.key == Bundle.INDEX }
        one[name] = bytes + ' '.code.toByte()
        assertNotEquals(bundle.hash, Bundle(one).hash, "one more byte moves it")
        val renamed = bundle.files.toMutableMap()
        renamed["js/app2.mjs"] = renamed.remove("js/app.mjs")!!
        assertNotEquals(bundle.hash, Bundle(renamed).hash, "a renamed file moves it")
    }
}
