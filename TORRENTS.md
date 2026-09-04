# Torrents on glass — design + build record (2026-09-01)

**Status: DESIGN SETTLED with Adam 2026-09-01 (evening); BUILT the same night and taken through a four-round review loop (`HANDOFF.md` §23.1–§23.4, ~114 findings, every one verified before a fix; PAUSED at Adam's word, not converged — the next pass starts from `980d832..HEAD`; battery green after round 4 — core 221 · desktop 9 · selfcheck 89 · 26 snapshots · lint 0; APK 18/0.18 staged, 0.16 the last observed installed — **0.26 supersedes it** since the Music build, its three reviews, the 2026-09-03 Now Playing root and the Games build).** The second
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
window's design rationale and build record; `IMPLEMENTATION.md` → "Torrents + the keyboard" is
what runs.

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
| 10 | settings placement (general rule) | **each app's notification toggles live in that app's Settings category, never in Global** — the unused Global rows for SMS/Mail/Music go; `Notify · Damage` stays global (the WM's own) |

Recorded so it is not re-pitched: RSS, categories on add, a shelf pipeline into `~/books`,
magnet/URL typing, `.torrent`-file hand-off from Files, a second tracker. All rejected or
deferred by Adam in this session.

## 2. Facts the design stands on (verified 2026-09-01)

**qBittorrent** (`HANDOFF.md` §23): 5.1.4 rebuilt with the `webui` USE flag,
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
`IconKind.TORRENTS` (theme names `qbittorrent`, `transmission`, `deluge`,
`network-transmit-receive`, `folder-download`; drawn fallback = a down-arrow into a tray) ·
`preferredHeight` from its Size row · title forms (short by design, §4.1): `transfers` (or the
active filter's label) · `details` · `browse` · `<category>` / `newest` · `"<query>"` ·
`torrent`.

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
  state word (`1.2 MB/s` while downloading, `seeding`, `stopped`, `error`, `checking`, `stalled`)
  in small bold. The periphery stays still: speeds live in the **lens**, rows show only the
  quantized bar and the state word, so a 2 s poll repaints at most the lens and the rows whose
  block or state changed. Lens (focused): 56 px icon · name bold · one detail line (`47% ·
  1.2 MB/s down · 0.3 MB/s up · 12 m left · 34 peers`, or for a seed `seeding · ratio 2.31 ·
  6d 3h seeded · 420 KB/s up`) · a 12-block bar
  · an 8-column speed history (the last 8 polls, quantized to 2 px steps). Sort default =
  ACTIVITY: **errors first** (they need attention), then downloads (by progress desc),
  checking, seeds (completed desc), stopped; NAME / ADDED / PROGRESS / SIZE on the Torrents
  menu. Filter: ALL / DOWNLOADING /
  SEEDING / STOPPED / ERRORS / **UNDER A WEEK** (finished, `seeding_time` < 7 d — the row's state
  word becomes `3d 4h seeded`, the lens says how long remains). Cursor rest: a descent lands on
  row 0 (§1.7); an ascent returns to the row it left from (the Files ascend rule), and the
  cursor follows its row's IDENTITY (the hash, or the wrap-end menu row) across live
  snapshots. Empty list = one honest row (`no transfers` / the state line).
- **transfer MENU** (tap on a row; MenuSurface, Details first — from the DETAILS document the
  harmless row 0 is Refresh instead): Details · Start or Stop (which one applies) · Recheck ·
  Open in Files (the torrent's content path — a Files `path:` deep link) · Open on PC · Delete (keep
  files) → confirm · **Delete + files** → confirm → a second confirm whose unrecoverable row
  sits at index 2 behind a disabled spacer (the Files purge shape). The destructive rows are
  last (§1.7).
- **Torrents MENU** (the wrap-end row): Browse TorrentLeech · Search TorrentLeech (opens the
  keyboard) · a recent search per row (up to 5, newest first — Adam wanted no history row in
  the keyboard; the recents live here) · Filter (cycles) · Sort (cycles) · Seeding < 1 week ·
  Refresh · Stats.
- **DETAILS** (Document): name · state + progress · speeds (+ ETA while downloading) · ratio +
  up/down totals · seeds/peers · added / completed · save path · tracker · category/tags · then
  `Files (n)` with every file as `name · size · nn%`. Wrapped at the live content width;
  relayout on font/size change; tap → the same actions menu with Refresh (the file list) as
  its harmless first row in place of Details.
- **CATEGORIES** (List): `Newest` first, then the 40 categories as `Group · Name` rows with a
  group icon (tv/film/game/app/book/music/education/animation/foreign) — the table is core's
  constant, never a provider call. Wrap-end row = the Browse menu (Search…, recents, Account;
  Sort and Refresh only inside a LISTING).
- **LISTING** (List; a category, or search results): name (fit) · size · `↑seeders ↓leechers`
  · a `FL` mark for freeleech; the lens adds snatches, age, category, tags. **Endless paging**:
  the next page (35/page) is fetched off-loop when the CURSOR comes within eight rows of the
  loaded end (never from a painted row — the panning list wraps its tail rows above the
  cursor); a dim `loading…` pseudo-row shows while a page is in flight; a failed page shows
  the failure in place and retries on a 5 s pacing (the Files viewer precedent — never a
  silent end; the demand runs from the window's own view on the loop, so it fires with the
  cursor resting on the loading row too, and never while the window is unfocused — a
  switcher preview of another window renders it too). A search with no matches keeps one
  honest row: `no results`.
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
  A line typed on a replica searches the same way. **Recorded exemption to "typed text
  always stages a confirm"**: a search is a read-only query, nothing outbound or destructive.
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
completion stamp, **kept across a removal** — a torrent that comes back with the same stamp is
a reload, one with a new stamp a real finish); the first run after install marks every
already-finished torrent announced (38 of them on 2026-09-01) so nothing storms; on a later restart
whatever finished while the service was down announces once. A shell that reconnects asks for
events since the last sequence it saw and replays what it missed (the host says `truncated`
when its 200-deep ring no longer reaches back that far). A host that restarted (new epoch):
a phone that was connected before replays the fresh log from its start — it holds only the
baseline finishes and what happened since — while a phone making its very first contact
adopts the current sequence with no replay, so a phone app restart never re-shows days of
old announcements.

### 3.3 Settings → Torrents

`Notify · done` · `Notify · errors` · `Poll` (1 s / 2 s / 5 s while the window is focused;
idle is always 15 s) · `Size` (global / 288 / 352 / 416 / 480). Font / Font size / Font style
/ Depth rows are added by the shell like every app. Persisted in the window's main record
(synced, last-write-wins).

### 3.4 Main summary (cheap, cached — §4.6)

Line 1: `2 downloading · 1.2 MB/s` (or `31 seeding · 420 KB/s up` when nothing downloads,
`idle` when empty); line 2: `31 seeding · 5 stopped · 1 error`; `progress` = the most advanced active
download (drives Main's block bar); `more` while transfers exist. A provider state line (`PC
unreachable 40s`, `qBittorrent unreachable 12s`, `TorrentLeech: login failed`) replaces line 1
— staleness is said with duration (§10.5).

### 3.5 State

`window.torrents` (main record, synced): level · cursors (the transfers cursor also by the
row's hash, so a restore lands on the same torrent after the list reorders) · filter · sort ·
open hash · browse category · search query + recents · the settings rows · the keyboard draft.
Restored positions wait for their content (a document's top for both the transfer and its
file list; a listing cursor for its page; the transfers row — by hash, by the wrap-end menu
row, or by index — for the first snapshot, once; resolved at once, on a live record or a
restart alike, when a snapshot is already at hand).
A live-synced record reloads its open document at once only while the window is focused;
unfocused, on the next activation. Nothing here has per-item state, so no sub-records.
`open("t:<hash>")` and `open("tl:<fid>")` synthesize the level path so back behaves as if
navigated by hand (§16.1).

## 4. Providers (§16.10)

```
TorrentsProvider (interface)                Local (PC)                       Remote (phone)
  stateLine() · snapshot() (cached)         QbtClient  ─ HTTP loopback       RemoteWin "torrents"
  eventsSince(seq, epoch)                   TorrentLeech ─ HTTPS + cookie    polls `snap` on its own
  setFocused(focused, paceMs) · refresh()   poll loop: 15 s idle, the        pacing (2 s focused,
  start/stop/recheck/delete(hashes,…)       fastest FOCUSED party's pace     15 s idle; woken by
  detail(hash)                              (local shell / phone tracked     attach and focus),
  tlCategories/tlBrowse/tlSearch/tlDetail   separately); event log +         pushes into the
  tlAdd(fid, stopped) / tlAccount()         announced set; `TorrentsService` window's listener;
  openOnPc(pathOrUrl)                       on the win channel               blobs for the bulk
```

The wire (`snap`): the phone sends its snapshot `v`ersion, the last event `since` it saw and
the host `epoch` it belongs to, plus `focused`/`pace`; the host answers `changed` (a blob with
the whole snapshot only when the version OR the epoch differs), the events since, `truncated`
when its ring no longer reaches back, and its own state line. The phone therefore reads at
most one host interval behind — a deliberate simplification over an on-demand `maxAge` poll.

- **`QbtClient`** (`java.net.HttpURLConnection` — core runs on Android too, no `java.net.http`):
  `sync/maindata` with `rid=0` — always a full update, deliberately (one request carries the
  list AND the session line, and a full answer for a few dozen torrents on loopback costs
  nothing while an incremental merge would be a bug surface; anything but a full update is
  refused), `properties` + `files` + `trackers`, `start` / `stop` / `recheck` / `delete`, `add`
  (multipart `.torrent` bytes + `savepath` / `stopped`), `app/version`. A refused request or a
  non-JSON body is an exception with the status and the first line of the body; a `Forbidden`
  triggers one login attempt when credentials are configured (they are not, on beardos) and a
  refused login latches for the process (five failures ban the address for an hour).
- **`TorrentLeech`**: login → cookie jar persisted in `~/.damage/tl-cookies.json` (0600),
  re-login once on a redirect to the login page, the login form, or an HTML page in place of
  the listing JSON — **at most one login a minute, enforced inside `login()` itself so every
  path is paced, the empty-cookie-jar path included** (a session dropped within a minute of a
  login, or a maintenance page, is reported, not logged into again) and **a refusal latches for
  the process only when the site answers the login POST with its login FORM** — any other
  answer is paced, never latched (the qBittorrent rule: a retrying listing must never post the
  credentials every five seconds; review rounds 3–4), then the request retried once; browse /
  search / detail / download / account; HTML parsed with a small stdlib tokenizer (no
  third-party parser), every expected landmark checked. Credentials come from
  `~/.damage/config.json` (`torrentleechUser` / `torrentleechPass`) — the standing secrets
  rule; nothing in the repo.
- **`LocalTorrentsProvider`**: owns both clients and the poll loop (15 s idle; the fastest
  focused party — the local window or the phone through the channel — sets the pace, and a
  driver's focus is released when its channel ends), diffs snapshots into events (done / error
  / added / removed), keeps the last 200 events with a monotonic sequence and a per-process
  epoch, persists the announced set. All qBittorrent and tracker I/O is off-loop; the window
  applies results through `runOnShell`. A desktop stack stop DETACHES its window (listener +
  focus) because the provider outlives the stack.
- **`TorrentsService` / `RemoteTorrentsProvider`** (`TorrentsNet.kt`): ops `snap` (above; the
  transfer list rides the **blob lane** — 38 torrents are small, a seedbox is not), `detail`,
  `start`, `stop`, `recheck`, `delete`, `tlcats`, `tlbrowse`, `tlsearch`, `tldetail`, `tladd`,
  `tlaccount`, `open`. The remote provider runs its own paced poll (focused/idle) and pushes
  into the window's listener; its `stateLine` is the channel's ("PC unreachable Ns") first, the
  host's own (`qBittorrent unreachable Ns`, `host answer not understood`) second. App-alone the
  window is honestly unavailable — a torrent client does not cache.

## 5. Failure discipline (the absolute rules, applied)

- Poll failures: the state line (`qBittorrent unreachable 12s`), never a notice storm; the
  first failure after health logs once; recovery clears it.
- Tracker failures: one title notice per attempt (`TorrentLeech login failed` / `format
  changed: …`), and a page fetch failure shows in the listing's loading row in place and
  retries on a 5 s pacing (an empty listing included). The state line is qBittorrent's; the
  tracker's health is visible where the tracker is used.
- Every menu action reports its result on the title (`stopped`, `added · <name>`, `deleted`)
  and its failure as a notice; one provider op at a time per window, refused loudly when busy.
- No timeouts: pacing loops and liveness decisions only (the `RemoteWin` contract); HTTP calls
  carry no read deadline — a stalled host is reported by the channel's liveness, not abandoned.
- Every dynamic string draws through `Draw.dynamic` / `Draw.fit` (torrent names are the
  wildest text this shell will ever see).

## 6. Tests and gates (as built — all green at `390a25c`)

- **core `TorrentsTest` ×7**: the QbtClient against a fake HTTP server (paths, `rid` handling,
  multipart add, 5.x verb names, error mapping); the TorrentLeech adapter against fixtures
  (listing JSON, a detail page with the real landmarks, a changed-format page → loud refusal;
  login redirect → one re-login); the local provider's event diff (done fires once, baseline
  suppresses, a re-add with the same stamp is silent and a new stamp announces, the error edge,
  sequence/epoch replay incl. the host restart); the window grammar over a fake provider
  (transfers → menu → details, the details menu's harmless row 0; browse → listing → torrent →
  add confirm → add called with the right fid; search via keyboard gestures; the under-a-week
  filter); persistence round trip and the continuity test (§16.4c); the remote provider through
  a real loopback host (the FilesTest rig), detach removing the listener.
- **core `KeyboardTest` ×22**: the `DESIGN.md` §4.8 contract (every row sums to twelve units in
  every layer and layout, every printable ASCII reachable, harmless rests, the row/key grammar,
  wrap on both axes, caret editing, the abc layout) plus the round-1 pins (live rows chunk
  8 → 4+4 and 13 is refused; a seven-row board fits every Size; uncovered glyphs display as `?`
  without moving the caret; a long prompt stays in the box; a shell stop hands the draft back
  and the restart has no keyboard).
- The review rounds' later pins — refusal before the surface opens, the harmless live-row
  heads, the failed first page's retry and the retried page landing, the login latch and the
  paced re-login, the surrogate-safe pan, a second ask refused while a keyboard is open — all
  sit in those two classes (they are the only test files the Torrents commits touched).
- **`--selfcheck`** (89 checks): `ScriptedTorrents` drives the whole walk — transfers → menu →
  details → browse → listing → page → add-confirm → keyboard search → the done notification —
  plus the ink budgets. Measured 2026-09-01: transfers list 9.0 %, details 6.4 %, the open
  keyboard 9–11 % (the list budget is ≤ 15 %).
- **`--snapshot`**: scenes 15–22 (transfers, lens+menu, details, categories, listing, torrent
  page, the keyboard at its two stages) — looked at, at true 1×; 26 scenes in all.
- `tools/lint.py` at 0, `:phone:assembleDebug` green; the jar restaged and the `damage` service
  restarted on the round-4 build; APK 18/0.18 staged at `390a25c` (0.26 since — see the status line; selfcheck 189 / 49 scenes at HEAD).

## 7. Build order — shipped

Built in the planned order in one pass (the keyboard + its shell wiring → `QbtClient` + the
provider + the window → the tracker adapter + browse/search/add → `TorrentsNet` + both
registrations + the harnesses + the Settings notify-row move → battery, deploy, docs), commit
`28997a8`; then the review rounds `73fdf81` · `4f5e6e0` · `980d832` · `390a25c` —
`HANDOFF.md` §23 is the record.
