# Review log — the finishing build (2026-08-25)

Every candidate finding from the review rounds (`HANDOFF.md` §8.3 H) is recorded here as it
is found, so a compacted session cannot lose one. Format per finding:

```
### R<round>.<n> <subsystem> — <one-line claim>
- candidate: <what the reviewer reported, with the trace / sim run it offered>
- verification: <what the builder re-ran or re-traced, and the result>
- verdict: CONFIRMED | NOT A DEFECT (why) | DESIGN INTENT (why)
- fix: <commit> | none
```

## Round 1 (2026-08-25, after commit 926b267)

Six fresh reviewers, one per subsystem, read-only, each told to verify every candidate with a
concrete trace: (a) transport base + prelude + mirror tee, (b) phone BLE glue + service,
(c) BlueZ glue against the library sources, (d) seam mirror stream + PathTransport,
(e) replica server + page + desktop preview, (f) shell changes (divergence check, decision 6,
host rows, keeper). Findings are recorded below as they are verified by the builder.

### R1.a — transport base + prelude + mirror tee (8 candidates)

- **R1.a1 CONFIRMED** — "capability gate aborted — link down" is classified as a firmware refusal by `ShellKeeper` and `PathTransport` (both match the substring "capability gate"); a link end during the ~200 ms capability wait would make the keeper terminal / disable the path for good. Verified: `CfwTransportBase` throws `LintError("capability gate aborted — …")` on the session-end marker; the refusal path throws "capability gate FAILED …" and emits `Fault("capability")`. Fix (with the base edits, after the remaining reviewers finish): a typed `CapabilityRefused` exception at the refusal site, the abort reworded "capability query ended early", consumers match the type or the exact refusal phrase (the seam carries text).
- **R1.a2 CONFIRMED** — a cancelled `start()` (a lost arbitration) catches the cancellation, enqueues an FB_RELEASE and calls `disconnectLink()`, which for `withContext(Dispatchers.IO)` throws at entry in a cancelled coroutine → the links stay up; the RELEASE would go out over them. Fix: rollback disconnect under `NonCancellable` (done in `BlueZTransport.disconnectLink` now; the base's rollback too, pending), RELEASE only when this session requested the lease.
- **R1.a3 CONFIRMED** — the maintenance loops enqueue on `running`, which is true from the end of `connectLink()`, before the settle and the prelude; `lastImageAtMs` is never reset per session, so the keepalive fires on the first 4 s tick. Fix (pending): gate the enqueuing loops on `started`, reset `lastImageAtMs` at session entry.
- **R1.a4 CONFIRMED** — hardware transports never lower `leaseHeld` (only `SimTransport` calls `setLease(false)`); the tee mirror's lease-expiry prediction is logged at DEBUG (dropped by `Log.minLevel`); the initial FB_ACQUIRE's write failure does not fail `start()`. Fix (pending): derive the lease from the mirror on the tick for tee transports, await the initial ACQUIRE's write, log mirror lease/warmup/launch events at INFO.
- **R1.a5 CONFIRMED** — sid-0x01 frames discarded during the prelude wait are logged at DEBUG. Fix (pending): WARN with flag and hex.
- **R1.a6 CONFIRMED (low)** — the model's `preludeSeen`/`layoutCreated` persist across the sim transport's start/stop cycles, so the strict prelude model only bites on the first session. Fix (pending): `linkReset()` on disconnect for the sim transport and tee mirrors (page + prelude are per connection; leases and broken sessions persist — firmware RAM outlives a BLE link).
- **R1.a7 CONFIRMED (low)** — `preludeMsgId` not reset at session entry. Fix (pending).
- **R1.a8 CONFIRMED (low)** — `LaunchMsg.msgIdOf` reports a malformed payload as absent. Fix (pending): log distinctly.
- Doc inconsistency noted by the reviewer: IMPLEMENTATION/HANDOFF say a failed `start()` bumps the epoch; the code sweeps without bumping (harmless). Fix: the docs.

### R1.c — BlueZ glue (12 candidates, all verified against the unpacked library and bluetoothd sources)

- **R1.c1 CONFIRMED** — dbus-java's 20 s default reply deadline on every call (a NO TIMEOUTS violation inherited from the library; an LE `Connect` may take the kernel's 20 s). Fix: `MethodCall.setDefaultTimeout(0)` in `BlueZDbus` init; a bus that ends completes pending calls with an error.
- **R1.c2 CONFIRMED** — a per-arm failure after `Connect` left that arm connected (the path was registered only after all checks). Fix: registered immediately after connect; the rollback finds it. Test added (`aFailureOnTheSecondArmReleasesBoth`, and the MTU test asserts the release).
- **R1.c3 CONFIRMED** — no cancellation point in the connect path (`withContext(IO)` around blocking calls, `Thread.sleep` poll). Fix: every link call in `runInterruptible(Dispatchers.IO)`, the services wait is a `delay()` poll over `probe()`, `ensureActive()` between arms; `disconnectLink` and `stopDiscovery` run under `NonCancellable`.
- **R1.c4 CONFIRMED** — (a) the discovery loop never re-checked that discovery still ran; (b) BlueZ lists previously connected devices by name even when they are not advertising, so a remembered pair in the case would be connected to (20 s per attempt). Fix: `discovering()` checked each poll (loud failure otherwise); a peer matches only when seen by the current scan (`rssi != null`) or already connected. Test added (`aRememberedPairThatIsNotAdvertisingIsNotConnectedTo`).
- **R1.c5 CONFIRMED** — the RSSI coroutine raced `running` and could persist across sessions; `DeviceManager` is not thread-safe. Fix: RSSI read on the maintenance tick (every 10th), DeviceManager map access behind one lock, blocking bus calls outside it.
- **R1.c6 CONFIRMED** — the `BluezAlreadyConnectedException` catch was unreachable (bus errors arrive as `DBusExecutionException`); `isPowered()`/`getAdapter()` could throw NPE/IOOBE instead of the intended message. Fix: `Powered`/`Discovering`/`Connected`/`ServicesResolved` read via `Properties.Get` (an exception is an exception), adapter lookup wrapped with the intended message.
- **R1.c7 CONFIRMED** — property read failures became `null`/`false` state. Fix: `probe()` throws on a failed read.
- **R1.c8 CONFIRMED** — a `Connected=false` before `running` was dropped. Fix: recorded in `droppedDuringConnect` and checked per arm.
- **R1.c9 CONFIRMED** — an exception in the signal handler ended on the executor's uncaught handler; handlers accumulated per stack rebuild. Fix: the handler body is wrapped and reports `Event.Failure` (→ `Fault("ble")`); `close()` unregisters; the process-wide bus stays open.
- **R1.c10 CONFIRMED (low)** — double introspection per lookup. Fix: the redundant `findBtDevicesByIntrospection` dropped.
- **R1.c11 CONFIRMED** — an unreadable MTU proceeded unchecked, against §8.2's "loud refusal otherwise". Fix: refused. Test added.
- **R1.c12** — fake-vs-real gaps: tests added for the per-arm failure rollback and for our own disconnect's `Connected=false` not counting as a link loss; the fake now reports `discovering()`/`probe()` like the real link.

### R1.e — replica server, page, desktop preview (12 candidates)

- **R1.e1 CONFIRMED** — `attach()` seeded `lastSent` with zeros and diffed, so black rows (or an all-black mirror) were never sent to a fresh or reconnecting page → stale rows shown as "online". Fix: both full panels on every attach; the page clears its canvases on open.
- **R1.e2 CONFIRMED** — a page that sent no input never re-attached to a rebuilt mirror. Fix: `attach()` on the 1 Hz status tick (synchronized).
- **R1.e3 CONFIRMED** — `switchTo` stopped the running stack before building the new one; a failing build left nothing driving and the reason unseen. Fix: build first, stop the old one only after construction succeeds, the failure shown in the strip and the page status; `stack` is an `AtomicReference`.
- **R1.e4 CONFIRMED** — `lastSent` copied from the live buffer at a different instant than the frame → a lost update. Fix: frames are built at send time and `lastSent` records exactly the bytes sent.
- **R1.e5 CONFIRMED** — key auto-repeat (page and Swing) turned a held Space into a stream of long-presses. Fix: `e.repeat` guard; a pressed-key set in the preview.
- **R1.e6 CONFIRMED (medium confidence on browser deltas)** — the 100 px notch threshold dropped every other notch on Linux Chrome and ignored Firefox's line mode. Fix: per `deltaMode`; a notch-sized pixel event is one notch.
- **R1.e7 CONFIRMED** — the strip `JLabel` cut long status text with "…" (NO TRUNCATION). Fix: a wrapping `JTextArea`, re-packed when its height changes.
- **R1.e8 CONFIRMED** — an unbounded per-client queue of full frames. Fix: per-client dirty marks; the frame is built at send time from the live mirror against what the client last received; status coalesced.
- **R1.e9 DESIGN INTENT** — the second click of a double-click is not a second tap (a deliberate trade; right-click is the double-tap). Documented in the page's help line; left as is.
- **R1.e10 CONFIRMED** — closing the preview window exited without stopping the stack (state within the 2 s save debounce lost, lease left to expire). Fix: the close button and a shutdown hook run an orderly stop.
- **R1.e11 CONFIRMED (low)** — the close handshake could race the sender; a dead `closedByUs` variable. Fix: the close frame is echoed synchronously; the variable removed.
- **R1.e12 CONFIRMED (low)** — Chrome's favicon request logged a token warning. Fix: an empty icon link; the favicon path is not logged.
- Incidental: the `SWEPT` constant holds a raw NUL byte, which makes `grep` treat the file as binary. Fix (pending, base edit): `"\u0000swept: "`.

### R1.d — seam mirror stream + arbitration + keeper (12 candidates)

- **R1.d1 CONFIRMED (design)** — the seam server ran `inner.start()` with `runBlocking` on its reader thread, so a client leaving mid-start (a lost arbitration) was never observed: the phone stayed paused with the driver slot held. And the race decided by completion time favoured PC-direct BLE whenever the phone was reachable (the seam path must pause the phone's shell and reconnect), against the contract. Fix: the server runs the inner start as a job and keeps reading (EOF/stop cancels it; the base's rollback disconnects); `PathTransport` holds a lower rank off entirely while a higher rank is ENGAGED (`Transport.engaged`: the seam client from the server's grant until its start completes or fails) and otherwise only a head start — the phone path wins by construction when reachable. Documented limit: a reachable phone that cannot itself see the glasses keeps the radio waiting (the status line says so). Test: `aLowerRankHoldsOffWhileAHigherRankIsEngaged`.
- **R1.d2 CONFIRMED** — "every path disabled → keeper terminal" was not implemented (message mismatch). Fix: `PathTransport` throws `CapabilityRefused` when no path is left; consumers test the type. Test: the all-refused case in `PathTransportTest`.
- **R1.d3 CONFIRMED** — unbounded outbox of raw panel bytes (~50–100× the flush payload), input/done queued behind them. Fix: per-arm marks, the frame built at send time against what the client last received, rows deflated (`Zl.deflate`), `rawLen` carried and checked; ordering preserved (the mark precedes the done). `SeamMirrorTest` still passes (equality after every flush, panel before done).
- **R1.d4 CONFIRMED** — same as a2/b2 (cancelled attempt skipped its disconnect). Fix: rollback and stop under `NonCancellable` in the base; the two glues' `disconnectLink` as well.
- **R1.d5 CONFIRMED** — the keeper's `pause`/`stop` cancelled the loop while it could be inside `shell.stop()`, leaving the shell stopped and the transport started for good. Fix (pending the shell report): the keeper's own stops under `NonCancellable`.
- **R1.d6 CONFIRMED** — forwarders launched dispatched; start-time events never forwarded. Fix: `UNDISPATCHED` launches; `Link(true)`/`Lease` re-emitted after attaching.
- **R1.d7 CONFIRMED** — client-side `rows × stride` could overflow; two policies for a malformed panel. Fix: `readCtl` validates `y0`, `rows`, `rawLen`; an inflate/size failure is a `Fault("seam")`.
- **R1.d8** — torn reads: confirmed safe (the diff runs under the sim's monitor on the listener path; the initial push self-heals).
- **R1.d9 CONFIRMED (low)** — `post()`'s catch was unreachable (`trySend` does not throw). Fix: the result is checked; a refused post is logged.
- **R1.d10 CONFIRMED (low)** — the keeper counts its own `stop()`'s `Link(false)`. Fix (pending): a `stopping` flag in the watcher.
- **R1.d11 CONFIRMED** — no `TCP_NODELAY`/`SO_KEEPALIVE`, no client-side stall report. Fix: both options on both ends; the client reports a `Fault("stall")` when a submitted flush has no done for 10 s (a report, nothing cancelled).
- **R1.d12 CONFIRMED (low)** — a failed start's close logged as a link error; `_state` read-copy-update. Fix: `closing=true` before the close; `updateState` under a lock.

### R1.b — phone BLE glue + service (12 candidates)

- **R1.b1** = a1 (typed refusal).
- **R1.b2 CONFIRMED** — a cancelled BLE start left both GATT connections open (Nordic's `.suspend()` on a cancelled continuation drops the request before it is enqueued), and the next scan then waited for lenses connected to us. Fix: `disconnectLink` under `NonCancellable`; any manager still connected at the start of `connectLink` is ended first.
- **R1.b3 CONFIRMED (AOSP `ScanManager`)** — an unfiltered scan does not run with the screen off, so a pocket-time link loss was only recovered when the screen came on. Fix: the scan is filtered on the remembered pair's addresses and advertised names (persisted in Prefs) whenever a pair is remembered; unfiltered only for a pair never seen, and the status says it needs the screen on.
- **R1.b4 CONFIRMED** — two keeper loops after a takeover ended (`kick()` unserialised, `loop` not volatile). Fix (pending the shell report): synchronized `kick`, volatile `loop`, `resume` clears `paused` inside it.
- **R1.b5** = d1.
- **R1.b6 CONFIRMED** — the RSSI poll raced `running` and could persist across sessions. Fix: read on the maintenance tick (non-blocking request with a callback).
- **R1.b7 CONFIRMED** — a target switch during a rebuild was dropped. Fix: queued and applied when the rebuild finishes.
- **R1.b8 CONFIRMED** — no `Log` sink on the phone: warnings and errors only reached stdout. Fix: a sink forwards everything to logcat and errors to an urgent notification, rate-limited per tag (10 s).
- **R1.b9 CONFIRMED (low)** — an unknown MTU (-1) proceeded on an assumption. Fix: the manager's own value decides, and is refused below 245.
- **R1.b10 CONFIRMED (low)** — a RIGHT drop while LEFT connected was detected only at the prelude write. Fix: both managers are checked after the loop.
- **R1.b11 CONFIRMED** — "scanning"/"connecting" never reached the status line. Fix: `LinkState.detail` (set by the base at each start step and by both glues during scan/connect; carried over the seam), shown by the phone's status/notification (2 s refresh), the desktop strip and the replica page's note.
- **R1.b12 CONFIRMED (low)** — `stopStack` closed the seam server before taking the keeper out. Fix: the keeper and shell are taken out first.

### R1.f — shell changes (8 candidates + 4 confirmations)

- **R1.f1 CONFIRMED** — the divergence check read the live mirror buffer with no synchronisation; a fail-open tick repainting the model's panel during the read could raise a false episode. Fix: `LensPanels.snapshot(arm)` (a copy under the mirror's lock; every implementation) and the check compares the snapshot.
- **R1.f2 CONFIRMED** — no backoff for recurring episodes (one urgent notice + one full keyframe per settle, unbounded). Fix: after 3 episodes without a quiet stretch (10 agreeing checks) the report stays on the status bar (`DIVERGE xN`) with no further notices or keyframes.
- **R1.f3** = d10 — the keeper counted its own stop. Fix: `selfStopping` around the keeper's stops (with d5's `NonCancellable`).
- **R1.f4 ALREADY FIXED by a4** — hardware transports derive `leaseHeld` from the mirror's fail-open model on the tick and emit `Lease(false)`; the reviewer read the pre-fix file.
- **R1.f5 CONFIRMED (low)** — the scan divided per pixel over 614k pixels on the shell loop. Fix: a 256-entry level table, an early-out first-difference scan (the count only when an episode is new).
- **R1.f6 DESIGN CALL, taken** — a notice that waited behind the wheel was shown and at once marked read when the commit target was its app. Decision: commit first (§4.5's auto-read applies), then show the next UNREAD notice (`Notifications.show()` skips read ones) — a box for the app just entered is not shown as new.
- **R1.f7 CONFIRMED (low)** — `allEntries[i]` unguarded; a restored cursor beyond a shrunken host list. Fix: `getOrNull` guards, the cursor clamped on restore.
- **R1.f8 ACCEPTED** — `isQuiescent()`/`quiescenceReport()` read loop-confined state from test threads (introspection only; a stale read is retried by the settle loops). Documented, not changed.
- f9–f12: confirmed correct (the keyframe after a divergence lands through the normal pump; the furl branch of decision 6 is unreachable but harmless; the wheel-open grace guard is dead but harmless; host-row identity and the quantiser are right).

**Round 1 summary.** 64 candidates across six reports; 58 confirmed and fixed in this round (the
arbitration's decision rule and the seam server's start were the two design-level ones), two
design calls taken, two accepted as-is, one already fixed by a sibling fix, one documentation
inconsistency corrected. Tests added: BlueZ (4 new), PathTransport (2 new), the seam and shell
tests re-run green. Round 2 follows on the areas that changed.
