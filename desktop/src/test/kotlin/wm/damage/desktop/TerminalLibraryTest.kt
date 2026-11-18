package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.web.ClassPathVendor

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B12: the PC's class path holds the terminal library at the catalog's pinned version — the module,
 * the sheet and the licence the route serves — and the licence is MIT (read from the jar's own LICENSE).
 */
class TerminalLibraryTest {
    @Test
    fun theClassPathHoldsTheThreeFilesAtThePinnedVersion() {
        val loader = TerminalLibraryTest::class.java.classLoader
        val pom = loader.getResourceAsStream(ClassPathVendor.POM)!!.use { String(it.readBytes()) }
        assertTrue("version=6.0.0" in pom, "the catalog's pin: $pom")
        val v = ClassPathVendor(loader)
        val mjs = String(v.file("xterm.mjs")!!)
        assertTrue("as Terminal}" in mjs, "the module exports Terminal")
        assertTrue(v.file("xterm.css")!!.isNotEmpty())
        val licence = String(v.file("LICENSE")!!)
        assertTrue(licence.contains("Permission is hereby granted, free of charge"), "the MIT permission notice")
        assertEquals(3, listOf("xterm.mjs", "xterm.css", "LICENSE").count { v.file(it) != null })
    }
}
