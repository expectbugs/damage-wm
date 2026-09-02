package wm.damage.desktop

import java.util.concurrent.CopyOnWriteArrayList
import wm.damage.core.windows.torrents.SessionStats
import wm.damage.core.windows.torrents.Snapshot
import wm.damage.core.windows.torrents.TFile
import wm.damage.core.windows.torrents.TlAccount
import wm.damage.core.windows.torrents.TlCategory
import wm.damage.core.windows.torrents.TlDetail
import wm.damage.core.windows.torrents.TlFile
import wm.damage.core.windows.torrents.TlItem
import wm.damage.core.windows.torrents.TlPage
import wm.damage.core.windows.torrents.TorrentEvent
import wm.damage.core.windows.torrents.TorrentLeech
import wm.damage.core.windows.torrents.TorrentsProvider
import wm.damage.core.windows.torrents.Transfer
import wm.damage.core.windows.torrents.TransferDetail

/**
 * A deterministic torrents provider for --selfcheck and --snapshot: six
 * transfers in every state that matters, a session line, two pages of a
 * tracker listing, a torrent page, an account — no qBittorrent, no network.
 * [fireDone] raises the done edge the selfcheck's notification check waits on.
 */
class ScriptedTorrents : TorrentsProvider {
    private val listeners = CopyOnWriteArrayList<TorrentsProvider.Listener>()
    val added = CopyOnWriteArrayList<String>()
    val ops = CopyOnWriteArrayList<String>()
    private var seq = 0L
    private var version = 1L
    val epoch = 4242L
    private val now = System.currentTimeMillis() / 1000

    var transfers: List<Transfer> = listOf(
        t("aa01", "ubuntu-26.04-desktop-amd64.iso", "downloading", 0.47, 6_400_000_000, dl = 1_250_000, up = 310_000, eta = 2_700, peers = 34, added = now - 3_600),
        t("bb02", "Debian.13.netinst.amd64.iso", "stalledDL", 0.12, 700_000_000, dl = 0, up = 0, eta = 8_640_000, peers = 0, added = now - 7_200),
        t("cc03", "Ted.Lasso.S04E05.720p.x264-FENiX", "uploading", 1.0, 580_159_815, dl = 0, up = 420_000, eta = 0, peers = 12,
            added = now - 86_400 * 2, completed = now - 86_400 * 2 + 1_800, seeding = 86_400 * 2 - 1_800, ratio = 2.31),
        t("dd04", "The Black Company - Glen Cook (epub)", "stalledUP", 1.0, 12_400_000, dl = 0, up = 0, eta = 0, peers = 0,
            added = now - 86_400 * 30, completed = now - 86_400 * 29, seeding = 86_400 * 29, ratio = 17.6),
        t("ee05", "Gentoo.stage3.amd64.openrc.tar.xz", "stoppedDL", 0.63, 280_000_000, dl = 0, up = 0, eta = 8_640_000, peers = 0, added = now - 86_400 * 5),
        t("ff06", "Missing.Files.Example.mkv", "missingFiles", 1.0, 1_400_000_000, dl = 0, up = 0, eta = 0, peers = 0,
            added = now - 86_400 * 12, completed = now - 86_400 * 11, seeding = 86_400 * 3),
    )

    var session = SessionStats(dlSpeed = 1_250_000, upSpeed = 730_000, dlSession = 4_100_000_000, upSession = 9_300_000_000,
        allDl = 2_100_000_000_000, allUl = 11_000_000_000_000, freeSpace = 2_900_000_000_000, ratio = "5.24",
        peers = 46, status = "connected", version = "v5.1.4")

    private fun t(hash: String, name: String, state: String, progress: Double, size: Long, dl: Long, up: Long, eta: Long,
        peers: Int, added: Long, completed: Long = 0, seeding: Long = 0, ratio: Double = 0.4) = Transfer(
        hash = hash, name = name, state = state, progress = progress, size = size,
        downloaded = (size * progress).toLong(), uploaded = (size * ratio * progress).toLong(),
        dlSpeed = dl, upSpeed = up, eta = eta, ratio = ratio, seeds = if (progress < 1.0) 8 else 0, seedsTotal = 145,
        peers = peers, peersTotal = 19, addedOn = added, completedOn = completed, seedingTime = seeding,
        savePath = "/home/user/Downloads", contentPath = "/home/user/Downloads/$name", category = "", tags = "",
        tracker = "https://tracker.torrentleech.org/a/announce",
    )

    private fun snap(): Snapshot = Snapshot(version, epoch, System.currentTimeMillis(), transfers, session, seq)

    override fun stateLine(): String = ""
    override fun snapshot(): Snapshot = snap()
    override fun addListener(l: TorrentsProvider.Listener) {
        if (!listeners.addIfAbsent(l)) return
        l.snapshot(snap())
        l.state("")
    }
    override fun removeListener(l: TorrentsProvider.Listener) { listeners.remove(l) }
    override fun setFocused(focused: Boolean, paceMs: Long) { ops.add("focus:$focused:$paceMs") }
    override fun refresh() { ops.add("refresh"); for (l in listeners) l.snapshot(snap()) }
    override fun eventsSince(seq: Long, epoch: Long): List<TorrentEvent> = emptyList()

    /** The ubuntu download finishes: the done edge the notification rides. */
    fun fireDone() {
        transfers = transfers.map {
            if (it.hash == "aa01") it.copy(state = "uploading", progress = 1.0, dlSpeed = 0, eta = 0,
                completedOn = System.currentTimeMillis() / 1000, seedingTime = 1) else it
        }
        version++
        val e = TorrentEvent(++seq, "done", "aa01", "ubuntu-26.04-desktop-amd64.iso", System.currentTimeMillis())
        for (l in listeners) { l.snapshot(snap()); l.event(e) }
    }

    override fun detail(hash: String): TransferDetail {
        val t = transfers.firstOrNull { it.hash == hash } ?: throw IllegalArgumentException("no such transfer $hash")
        return TransferDetail(hash, listOf(
            TFile(t.name, t.size, t.progress, 1),
            TFile("README.txt", 4_096, 1.0, 1),
        ), comment = "scripted", createdOn = t.addedOn, pieces = 2048, pieceSize = 4_194_304,
            trackers = listOf(t.tracker))
    }

    private fun mutate(what: String, hashes: List<String>, f: (Transfer) -> Transfer) {
        ops.add("$what:${hashes.joinToString("|")}")
        transfers = transfers.map { if (it.hash in hashes) f(it) else it }
        version++
        for (l in listeners) l.snapshot(snap())
    }

    override fun start(hashes: List<String>) = mutate("start", hashes) { it.copy(state = if (it.progress >= 1.0) "uploading" else "downloading") }
    override fun stop(hashes: List<String>) = mutate("stop", hashes) { it.copy(state = if (it.progress >= 1.0) "stoppedUP" else "stoppedDL", dlSpeed = 0, upSpeed = 0) }
    override fun recheck(hashes: List<String>) = mutate("recheck", hashes) { it.copy(state = if (it.progress >= 1.0) "checkingUP" else "checkingDL") }
    override fun delete(hashes: List<String>, withFiles: Boolean) {
        ops.add("delete:${hashes.joinToString("|")}:$withFiles")
        transfers = transfers.filter { it.hash !in hashes }
        version++
        for (l in listeners) l.snapshot(snap())
    }

    override fun tlCategories(): List<TlCategory> = TorrentLeech.CATEGORIES

    private fun item(i: Int, cat: Int, name: String): TlItem = TlItem(
        fid = "2418${26_800 + i}", name = name, filename = name.replace(' ', '.') + ".torrent", categoryId = cat,
        size = 700_000_000L * (i + 1), seeders = 145 - i * 3, leechers = 19 + i, snatched = 234 + i * 7,
        addedAt = stamp(6 + i),   // "Nh ago" stays stable day after day (the snapshot scene)
        tags = listOf("Linux", "amd64", if (i % 5 == 0) "FREELEECH" else "x86"),
        freeleech = i % 5 == 0,
    )

    /** The site's UTC timestamp text, [hoursAgo] before now. */
    private fun stamp(hoursAgo: Int): String =
        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(hoursAgo.toLong())
            .withSecond(0).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    private val names = listOf("Ubuntu 26.04 Desktop amd64", "Fedora Workstation 44 Live x86_64", "Debian 13 DVD amd64",
        "Gentoo LiveGUI USB 2026", "Arch Linux 2026.09.01 ISO", "openSUSE Tumbleweed DVD", "Linux Mint 23 Cinnamon",
        "NixOS 26.05 Graphical", "Alpine Linux 3.22 Extended", "Void Linux Live xfce", "Kali Linux 2026.3 Installer",
        "Manjaro KDE 26 ISO", "Pop OS 26.04 NVIDIA", "elementary OS 9", "Zorin OS 18 Core", "EndeavourOS Mercury",
        "Garuda Dragonized Gaming", "Slackware 15.1 DVD", "FreeBSD 15.0 RELEASE amd64 disc1", "OpenBSD 7.8 install",
        "Qubes OS 4.3 x86_64", "Tails 7.1", "Rocky Linux 10 DVD", "AlmaLinux 10 x86_64 boot", "CentOS Stream 10",
        "Ventoy 1.1 ISO pack", "Clonezilla Live 3.3", "GParted Live 1.7", "SystemRescue 12", "Batocera 41 x86_64",
        "RetroPie 4.9 image", "Raspberry Pi OS 2026-08 arm64", "Armbian 26 Orange Pi 5", "LibreELEC 13", "Proxmox VE 9 ISO")

    override fun tlBrowse(categoryId: Int?, page: Int, sort: String): TlPage {
        ops.add("browse:${categoryId ?: 0}:$page:$sort")
        val cat = categoryId ?: 23
        val all = names.mapIndexed { i, n -> item(i, cat, n) }
        val per = 20
        val from = (page - 1) * per
        return TlPage(all.drop(from).take(per), page, per, all.size)
    }

    override fun tlSearch(query: String, page: Int, sort: String): TlPage {
        ops.add("search:$query:$page:$sort")
        val hits = names.filter { it.contains(query, ignoreCase = true) }.mapIndexed { i, n -> item(i, 23, n) }
        return TlPage(if (page == 1) hits else emptyList(), page, 35, hits.size)
    }

    override fun tlDetail(fid: String): TlDetail {
        val i = (fid.removePrefix("2418").toIntOrNull() ?: 26_800) - 26_800
        val name = names.getOrElse(i) { "Torrent $fid" }
        return TlDetail(fid, name, "Apps · PC-ISO", "3.1 GB", 145, 19, 234, "1st September 2026 20:15:05 (an hour ago)",
            "Anonymous", listOf("Linux", "amd64", "FREELEECH"),
            "$name — the live image with the installer. Boots on UEFI and legacy BIOS; the checksum is in the NFO.\n\nSee the release notes for the kernel and desktop versions.",
            "Release: $name\nFormat: ISO 9660 hybrid\nSHA256: 3f1e…c9a2\nSize: 3.1 GB\n\nEnjoy.",
            listOf(TlFile("${name.replace(' ', '-').lowercase()}.iso", "3.1 GB"), TlFile("SHA256SUMS", "1 KB")),
            "https://www.torrentleech.org/torrent/$fid")
    }

    override fun tlAdd(fid: String, stopped: Boolean): String {
        added.add("$fid:$stopped")
        val i = (fid.removePrefix("2418").toIntOrNull() ?: 26_800) - 26_800
        val name = names.getOrElse(i) { "Torrent $fid" }
        transfers = transfers + t("ad$fid", name, if (stopped) "stoppedDL" else "downloading", 0.0, 3_300_000_000, dl = 0, up = 0,
            eta = 8_640_000, peers = 0, added = System.currentTimeMillis() / 1000)
        version++
        for (l in listeners) l.snapshot(snap())
        return name
    }

    override fun tlAccount(): TlAccount = TlAccount("glassuser", "10.44 TB", "605.58 GB", "17.648", "4,416.88", "Extreme User")
    override fun openOnPc(target: String) { ops.add("open:$target") }
}
