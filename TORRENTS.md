# Torrents on glass — design + plan (2026-09-01)

**Status: DESIGN SETTLED with Adam 2026-09-01 (evening); BUILT the same night (`HANDOFF.md` §23; battery green — core 213 · selfcheck 89 · 26 snapshots); the review loop is next.** The second
window of the app wave after Files (`EXPLOSION.md` §20's wow order: Games · **Torrents** ·
Files ✅ · Music · …). Not a G2CC conversion — G2CC never had a torrent window — so
`WINDOWS.md` step 2 has nothing to mine; Reader, Tmux and Files are the precedents.

His intent, verbatim (§19): *"the ability to log into and browse my private torrent site and
add torrents to qbittorrent all within G2. Especially useful for things like linux distros I
want to try and other large downloads better done via torrent."* And the build rule he set for
this and every window after it: **no v1/v1.5 staging — "every app we add to DamageWM gets
completely built to its best state before we move on … complete and polished and as visually
appealing as we can make it."**

Precedence: `overview.md` facts · `CLAUDE.md` rules · `DESIGN.md` shell design (the keyboard
is §4.8 there) · `EXPLOSION.md` §16 contract · `WINDOWS.md` checklist. This file is the
window's design rationale and build plan; `IMPLEMENTATION.md` → "Torrents" is what runs.

---

## 1. Verdicts (the refinery, 2026-09-01)

| # | question | verdict |
|---|---|---|
| 1 | which tracker | **TorrentLeech only** for this iteration; the adapter seam stays generic |
| 2 | browse or search | **both** — browse by category, search through the new on-glass keyboard |
| 3 | delete-with-data from glass | yes: keep-files behind one confirm, **with data behind a double confirm**, neither at cursor rest (§1.7) |
| 4 | what "done" means | the download-finished edge, announced for every torrent (browser-added too); first run baselines the existing ones; **toggles live in Settings → Torrents** |
| 5 | add sources beyond the tracker | **no** — no magnet/URL typing, no Files → Torrents hand-off; TorrentLeech is the only source |
| 6 | categories / shelf glue | **no** — everything lands in `~/Downloads` exactly as qBittorrent already does; organization is a future project of his |
| 7 | poll cadence | 2 s focused / 15 s idle, host-side, pull with a version cursor |
| 8 | RSS / auto-watch (T.10) | **never** — *"i never automate torrenting"* |
| 9 | Stats | session totals + free space + his TL account (ratio, points, class), **plus a list of torrents seeding for less than a week** — TL wants a week of seed time before a snatch is safe from hit-and-run penalties |
| 10 | settings placement (general rule) | **each app's notification toggles live in that app's Settings category, never in Global** — the dead Global rows for SMS/Mail/Music go; `Notify · Damage` stays global (the WM's own) |

Recorded so it is not re-pitched: RSS, categories on add, a shelf pipeline into `~/books`,
magnet/URL typing, `.torrent`-file hand-off from Files, a second tracker. All rejected or
deferred by Adam in this session.

## 2. Facts the design stands on (verified 2026-09-01)

**qBittorrent** — `HANDOFF.md` §23 / session memory: 5.1.4 rebuilt with the `webui` USE flag,
Web API **2.11.4** on `http://127.0.0.1:8090`, loopback only, localhost auth bypass on (the
`damage` service runs as the same user on the same box — **no credentials anywhere in our
code path**). Read from the 5.1.4 source, not remembered: 5.x renamed the verbs to
`torrents/stop` and `torrents/start` and the states to `stoppedDL` / `stoppedUP`; the full
state set is downloading · stalledDL · queuedDL · stoppedDL · metaDL · forcedMetaDL ·
checkingDL · forcedDL · uploading · stalledUP · queuedUP · stoppedUP · checkingUP · forcedUP ·
error · missingFiles · moving · checkingResumeData. `sync/maindata?rid=N` is the incremental
poll (rid / full_update / torrents / torrents_removed / server_state). `torrents/add` takes a
newline list of URLs **or** the `.torrent` bytes as multipart file data, plus savepath /
category / tags / stopped; it answers `Ok.` or `Fails.`. `torrents/delete` takes `hashes` +
`deleteFiles`. `torrents/properties` and `torrents/files` give the detail. The finished edge
is libtorrent's `finished`/`seeding` state; `completion_on` and `seeding_time` (seconds spent
finished) are in every info row — the latter is what the "seeding under a week" list reads.

**TorrentLeech** — probed live, read-only, one login:

- Login: a form POST of username/password to the account login path; a session cookie comes
  back; no bot challenge for a normal browser user agent.
- Browse and search share **one JSON endpoint the site's own UI uses**:
  `/torrents/browse/list/` + optional `query/<q>/` + optional `categories/<id[,id]>/` +
  `orderby/<added|seeders|size|name>/order/<asc|desc>/page/<n>`. The answer carries
  `numFound`, `perPage` (35), `page`, `orderBy`, `order` and `torrentList[]` rows with fid,
  filename, name, addedTimestamp, categoryID, size, completed (snatches), seeders, leechers,
  numComments, tags[], new, imdbID, rating, genres, tvmazeID, igdbID, animeID,
  download_multiplier (0 = freeleech) and commentsDisabled.
- Categories: **40 in 9 groups**, read out of the site's JS bundle (Movies 11 · TV 3 · Games 13
  · Apps 4 · Education 1 · Animation 2 · Books 2 · Music 2 · Foreign 2). The table lives in
  `TorrentLeech.kt` with that lineage comment; a category id the table does not know is shown
  as its number, never dropped.
- The torrent page (`/torrent/<fid>`) is HTML: a **Torrent Info** table (category, added, size,
  peers, snatches, uploader, tags), a description block, the **NFO** text, and a **files** table
  (name, size). Download is `/download/<fid>/<filename>` with the session cookie.
- The profile page carries uploaded / downloaded / ratio / TL points / class — the account
  Stats row. ⚠ It also shows the passkey and e-mail in plain text: the adapter reads only the
  five stats fields and never stores the page.
- Honesty about this API: it is internal and undocumented. **Every parse refuses what it does
  not recognize and says so** ("TorrentLeech format changed: …") — a markup change produces a
  loud notice, never a quietly empty list. The adapter never guesses field meanings.

## 3. The window (`TorrentsWindow`, id `torrents`)

**Declares:** needs **HOST** · face **Fira Sans** (dense lists; the LIST role) · icon
`IconKind.TORRENTS` (theme names `qbittorrent`, `transmission`, `network-transmit-receive`;
drawn fallback = a down-arrow into a tray) · `preferredHeight` from its Size row · title forms
(short by design, §4.1): `transfers` · `details` · `browse` · `<category>` · `"<query>"` ·
`torrent` · `stats`.

### 3.1 Levels and grammar

```
TRANSFERS (List, root)  ──tap──▶ transfer MENU ──Details──▶ DETAILS (Doc) ──tap──▶ actions MENU
   │ wrap-end row "Torrents" ──tap──▶ Torrents MENU
   │      Browse TorrentLeech ▶ CATEGORIES (List) ──tap──▶ LISTING (List) ──tap──▶ TORRENT (Doc) ──tap──▶ add MENU
   │      Search TorrentLeech ▶ KEYBOARD ──↵──▶ LISTING (the same rows, title "query")
   │      Filter · Sort · Refresh · Stats · Seeding < 1 week
   ◀── double-tap backs one level everywhere; the keyboard's own back is §4.8
```

- **TRANSFERS** — rows: 20 px category icon · name (fit) · a 10-block progress bar · a short
  state word (`↓ 1.2 MB/s`, `seeding`, `stopped`, `error`, `checking`) in small bold. The
  periphery stays still: speeds live in the **lens**, rows show only the quantized bar and the
  state word, so a 2 s poll repaints at most the lens and the rows whose block or state changed.
  Lens (focused): 56 px icon · name bold · one detail line (`47% · 1.2 MB/s ↓ 0.3 ↑ · 12 m left
  · 34 peers`, or for a seed `seeding · ratio 2.31 · 6d 3h seeded · 145 peers`) · a 12-block bar
  · an 8-column speed history (the last 8 polls, quantized to 2 px steps). Sort default =
  ACTIVITY: downloading first (by progress desc), then seeding (completed desc), then stopped,
  then errors; NAME / ADDED / PROGRESS / SIZE on the Torrents menu. Filter: ALL / DOWNLOADING /
  SEEDING / STOPPED / ERRORS / **UNDER A WEEK** (finished, `seeding_time` < 7 d — the row's state
  word becomes `3d 4h seeded`, the lens says how long remains). Cursor rest = row 0 after every
  level change (§1.7). Empty list = one honest row (`no transfers` / the state line).
- **transfer MENU** (tap on a row; MenuSurface, Details first): Details · Start or Stop (which
  one applies) · Recheck · Open in Files (the payload path — a Files `path:` deep link) · Open
  on PC · Delete (keep files) → confirm · **Delete with files** → confirm → second confirm. The
  destructive rows are last (§1.7).
- **Torrents MENU** (the wrap-end row): Browse TorrentLeech · Search TorrentLeech (opens the
  keyboard) · a recent search per row (up to 5, newest first — Adam wanted no history row in
  the keyboard; the recents live here) · Filter (cycles) · Sort (cycles) · Seeding < 1 week ·
  Refresh · Stats.
- **DETAILS** (Document): name · state + progress · speeds + ETA · ratio + up/down totals ·
  seeds/peers · added / completed · save path · tracker · category/tags · then `Files (n)` with
  every file as `name · size · nn%`. Wrapped at the live content width; relayout on font/size
  change; tap → the same actions menu (minus Details).
- **CATEGORIES** (List): `Newest` first, then the 40 categories as `Group · Name` rows with a
  group icon (tv/film/game/app/book/music/education/animation/foreign). Wrap-end row = the
  Browse menu (Search…, recents, Refresh).
- **LISTING** (List; a category, or search results): name (fit) · size · `↑seeders ↓leechers`
  · a `FL` mark for freeleech; the lens adds snatches, age, category, tags. **Endless paging**:
  a listing near its loaded end fetches the next page (35/page) off-loop and appends; a dim
  `loading…` pseudo-row shows while a page is in flight; a failed page shows the failure in
  place and retries on a 5 s pacing (the Files viewer precedent — never a silent end).
- **TORRENT** (Document, a tracker item): name · category · size · seeders/leechers ·
  snatches · added · uploader · tags · freeleech · `Description` + the text · `NFO` (mono) ·
  `Files (n)`. Tap → **add MENU**: Add to qBittorrent → confirm (`Add '<name>'? Cancel / Add →
  ~/Downloads`) · Add stopped → confirm · Open on PC (the tracker page). The add downloads the
  `.torrent` host-side with the session cookie and hands the bytes to qBittorrent (never a URL
  that needs cookies on qBittorrent's side); success = a title notice + the transfer appears in
  TRANSFERS + its own done-notification later.
- **Search**: `openKeyboard(title "search torrentleech", initial = the last draft)`; ↵ runs the
  search and lands in LISTING titled `"query"`; the query joins the recents (max 10, deduped,
  synced with the window record). Cancel keeps the draft for the next open (his verdict 4).
- **Stats** (a menu of read-only rows): qBittorrent `↓ speed · ↑ speed`, session down/up,
  all-time ratio, free space, peers, connection status, version; TorrentLeech uploaded /
  downloaded / ratio / points / class. Computed off-loop, delivered as a notice if the window
  lost the screen meanwhile (the Files Stats shape).

### 3.2 Notifications (§16.5 source `torrent`)

| event | body | deep link | toggle (Settings → Torrents) |
|---|---|---|---|
| done (finished edge) | `done · <name>` | `t:<hash>` → TRANSFERS at the row + DETAILS | Notify · done (default on) |
| error / missing files (edge) | `error · <name>` | same | Notify · errors (default on) |

Coalescing key = the hash. Announcements are decided **host-side, once**, so the phone shell
and a PC standby shell agree; the announced set persists in `~/.damage/torrents.json` (hash →
completion stamp); the first run after install marks every already-finished torrent announced
(38 of them today) so nothing storms. A shell that reconnects asks for events since the last
sequence it saw and replays what it missed; a host that restarted (new epoch) hands out its
current sequence with no replay — a missed announcement is recoverable from the list, a
duplicate storm is not.

### 3.3 Settings → Torrents

`Notify · done` · `Notify · errors` · `Poll` (1 s / 2 s / 5 s while the window is focused;
idle is always 15 s) · `Size` (global / 288 / 352 / 416 / 480). Font / Font size / Font style
/ Depth rows are added by the shell like every app. Persisted in the window's main record
(synced, last-write-wins).

### 3.4 Main summary (cheap, cached — §4.6)

Line 1: `2 downloading · 1.2 MB/s` (or `31 seeding` when nothing downloads, `idle` when
empty); line 2: `31 seeding · 5 stopped · 1 error`; `progress` = the most advanced active
download (drives Main's block bar); `more` while transfers exist. A provider state line (`PC
unreachable 40s`, `qBittorrent unreachable 12s`, `TorrentLeech: login failed`) replaces line 1
— staleness is said with duration (§10.5).

### 3.5 State

`window.torrents` (main record, synced): level · cursors · filter · sort · open hash · browse
category · search query + recents · the settings rows · the keyboard draft. Nothing here has
per-item state, so no sub-records. `open("t:<hash>")` and `open("tl:<fid>")` synthesize the
level path so back behaves as if navigated by hand (§16.1).

## 4. Providers (§16.10)

```
TorrentsProvider (interface)                Local (PC)                       Remote (phone)
  stateLine()                               QbtClient  ─ HTTP loopback       RemoteWin "torrents"
  snapshot(maxAgeMs) / events(sinceSeq)     TorrentLeech ─ HTTPS + cookie    polls `snap` on its own
  start/stop/recheck/delete(hashes,…)       poll loop 15 s + demand polls    pacing (2 s focused,
  detail(hash)                              event log + announced set        15 s idle), pushes
  tlCategories/tlBrowse/tlSearch/tlDetail   `TorrentsService` on the win     into the window's
  tlAdd(fid, stopped) / tlAccount()         channel (`{"t":"win","win":      listener; blobs for
  openOnPc(pathOrUrl)                       "torrents"}`)                    listings/details
```

- **`QbtClient`** (`java.net.HttpURLConnection` — core runs on Android too, no `java.net.http`):
  `maindata` with `rid` (the snapshot), `info`, `properties` + `files`, `start` / `stop` /
  `recheck` / `delete`, `add` (multipart `.torrent` bytes + `savepath` / `stopped`),
  `transfer/info`, `app/version`. A refused request or a non-JSON body is an exception with the
  status and the first line of the body; a `Forbidden` triggers one login attempt when
  credentials are configured (they are not, on beardos) and is otherwise reported as such.
- **`TorrentLeech`**: login → cookie jar persisted in `~/.damage/tl-cookies.json` (0600),
  re-login once on a redirect to the login page or a non-JSON answer, then the request retried
  once; browse / search / detail / download / account; HTML parsed with a small stdlib
  tokenizer (no third-party parser), every expected landmark checked. Credentials come from
  `~/.damage/config.json` (`torrentleechUser` / `torrentleechPass`) — the standing secrets
  rule; nothing in the repo.
- **`LocalTorrentsProvider`**: owns both clients and the poll loop (15 s pacing; `snapshot(maxAge)`
  polls at once when the cached snapshot is older — the window's focused pacing rides this
  through `setFocused`), diffs snapshots into events (done / error / added / removed), keeps the
  last 200 events with a monotonic sequence and a per-process epoch, persists the announced
  set. All qBittorrent and tracker I/O is off-loop; the window applies results through
  `runOnShell`.
- **`TorrentsService` / `RemoteTorrentsProvider`** (`TorrentsNet.kt`): ops `snap` (args maxAge,
  since, epoch → a JSON snapshot + events; the transfer list rides the **blob lane** — 38
  torrents are small, a seedbox is not), `detail`, `start`, `stop`, `recheck`, `delete`,
  `tlcats`, `tlbrowse`, `tlsearch`, `tldetail`, `tladd`, `tlaccount`, `open`. The remote
  provider runs its own paced poll (focused/idle) and pushes into the window's listener; its
  `stateLine` is the channel's ("PC unreachable Ns"). App-alone the window is honestly
  unavailable — a torrent client does not cache.

## 5. Failure discipline (the absolute rules, applied)

- Poll failures: the state line (`qBittorrent unreachable 12s`), never a notice storm; the
  first failure after health logs once; recovery clears it.
- Tracker failures: one title notice per attempt (`TorrentLeech: login failed` / `format
  changed: …`) and the state line while it persists; a page fetch failure shows in the listing
  in place and retries on a 5 s pacing.
- Every menu action reports its result on the title (`stopped`, `added · <name>`, `deleted`)
  and its failure as a notice; one provider op at a time per window, refused loudly when busy.
- No timeouts: pacing loops and liveness decisions only (the `RemoteWin` contract); HTTP calls
  carry no read deadline — a stalled host is reported by the channel's liveness, not abandoned.
- Every dynamic string draws through `Draw.dynamic` / `Draw.fit` (torrent names are the
  wildest text this shell will ever see).

## 6. Tests and gates

- **core `TorrentsTest`**: the QbtClient against a fake HTTP server (paths, `rid` handling,
  multipart add, 5.x verb names, error mapping); the TorrentLeech adapter against fixtures
  (listing JSON, a detail page with the real landmarks, a changed-format page → loud refusal;
  login redirect → one re-login); the local provider's event diff (done fires once, baseline
  suppresses, error edge, sequence/epoch replay rules); the window grammar over a fake provider
  (transfers → menu → details; browse → listing → torrent → add confirm → add called with the
  right fid; search via keyboard gestures; the under-a-week filter); persistence round trip and
  the continuity test (§16.4c); the remote provider through a real loopback host (the FilesTest
  rig).
- **core `KeyboardTest`**: `DESIGN.md` §4.8's list.
- **`--selfcheck`**: a `ScriptedTorrents` provider drives the whole walk plus the ink budgets
  (transfers list ≤ 15 %, keyboard measured and reported).
- **`--snapshot`**: scenes 15–21 (transfers, lens+menu, details, categories, listing, keyboard,
  torrent page) — looked at, at true 1×.
- `tools/lint.py` at 0, `:phone:assembleDebug` green, then `stageJar` + service restart and a
  staged APK (bump per install).

## 7. Build order

1. Shell: `KeyboardSurface` + `ShellServices.openKeyboard` + the Global layout row + Tmux
   "Type…" + Files rename/new-folder through it + tests + scenes.
2. `QbtClient` + `LocalTorrentsProvider` + `TorrentsWindow` TRANSFERS/DETAILS/menus/Stats +
   notifications + Settings rows + Main summary (buildable and testable with the WebUI alone).
3. `TorrentLeech` adapter + CATEGORIES/LISTING/TORRENT/search/add.
4. `TorrentsNet` + the phone registration; desktop registration (auto + host-only), SelfCheck,
   Snapshot, icons, the Settings notify-rows move.
5. The battery, the deploy, the docs (`IMPLEMENTATION.md`, `DESIGN.md` §4.8, `EXPLOSION.md`
   §19 banner, `WINDOWS.md`, `REMINDER.md`, `HANDOFF.md` §23), then the review loop.
