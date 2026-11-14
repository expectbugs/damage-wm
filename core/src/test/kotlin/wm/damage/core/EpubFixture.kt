package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Build S1-1, P5: a made-up EPUB for the document and index tests — spine documents in order (each `name -> body`,
 * wrapped in `<html><body>`), images by zip path, an optional NCX and cover. Nothing here is from a real book.
 */
internal object EpubFixture {
    fun write(
        file: Path,
        docs: List<Pair<String, String>>,
        images: Map<String, ByteArray> = emptyMap(),
        titles: List<String>? = null,
        coverMeta: String? = null,
        extraManifest: String = "",
        title: String = "Fixture",
    ): Path {
        Files.createDirectories(file.toAbsolutePath().parent)
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            fun put(name: String, body: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            val manifest = StringBuilder()
            docs.forEachIndexed { i, (name, _) -> manifest.append("""<item id="d$i" href="$name" media-type="application/xhtml+xml"/>""") }
            images.keys.forEachIndexed { i, name -> manifest.append("""<item id="img$i" href="$name" media-type="image/png"/>""") }
            if (titles != null) manifest.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            manifest.append(extraManifest)
            val spine = docs.indices.joinToString("") { """<itemref idref="d$it"/>""" }
            val meta = coverMeta?.let { """<meta name="cover" content="$it"/>""" } ?: ""
            put("OEBPS/content.opf", ("""<package><metadata><dc:title>$title</dc:title><dc:creator>Someone</dc:creator>$meta</metadata>""" +
                """<manifest>$manifest</manifest><spine>$spine</spine></package>""").toByteArray())
            if (titles != null) put("OEBPS/toc.ncx", ("<ncx><navMap>" + docs.indices.joinToString("") {
                """<navPoint><navLabel><text>${titles[it]}</text></navLabel><content src="${docs[it].first}"/></navPoint>"""
            } + "</navMap></ncx>").toByteArray())
            for ((name, body) in docs) put("OEBPS/$name", "<html><head><title>x</title></head><body>$body</body></html>".toByteArray())
            for ((name, bytes) in images) put("OEBPS/$name", bytes)
        }
        return file
    }

    /** Overwrite the middle of the stored (deflated) data of entry [name] in [file], so reading its bytes fails
     *  while the archive's directory still lists it. Returns true when the reading fails as intended. */
    fun spoilEntry(file: Path, name: String): Boolean {
        val bytes = Files.readAllBytes(file)
        val key = name.toByteArray()
        // the local header: PK\3\4, then 26 bytes, the name, the extra field; the data follows
        var at = -1
        for (i in 0..bytes.size - 30 - key.size) {
            if (bytes[i] != 0x50.toByte() || bytes[i + 1] != 0x4B.toByte() || bytes[i + 2] != 3.toByte() || bytes[i + 3] != 4.toByte()) continue
            val nameLen = (bytes[i + 26].toInt() and 0xFF) or ((bytes[i + 27].toInt() and 0xFF) shl 8)
            if (nameLen != key.size) continue
            if ((0 until nameLen).all { bytes[i + 30 + it] == key[it] }) { at = i; break }
        }
        require(at >= 0) { "no local header for $name" }
        val nameLen = (bytes[at + 26].toInt() and 0xFF) or ((bytes[at + 27].toInt() and 0xFF) shl 8)
        val extraLen = (bytes[at + 28].toInt() and 0xFF) or ((bytes[at + 29].toInt() and 0xFF) shl 8)
        val data = at + 30 + nameLen + extraLen
        for (i in 0 until 24) bytes[data + 2 + i] = 0xFF.toByte()
        Files.write(file, bytes)
        return try {
            java.util.zip.ZipFile(file.toFile()).use { z -> z.getInputStream(z.getEntry(name)).readBytes() }
            false
        } catch (e: java.util.zip.ZipException) {
            true
        }
    }

    /** Bytes that deflate to a long stream (so the spoiled middle is inside the data). */
    fun noise(n: Int, seed: Long = 7): ByteArray {
        val r = java.util.Random(seed)
        return ByteArray(n) { r.nextInt(256).toByte() }
    }
}
