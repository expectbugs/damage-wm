# Where we are, and what to do next

**Updated 2026-08-18.** This is the orientation file. Read it first, then `overview.md` (facts),
`CLAIMS.md` (how well we know them), `CLAUDE.md` (rules), `DESIGN.md` (the shell).

---

## ✅ Done

| phase | state |
|---|---|
| Heavy research | **CLOSED 2026-08-17.** `overview.md`, ~1,530 lines |
| Full documentation | **CLOSED.** `CLAIMS.md` grades every load-bearing claim; `CAPABILITIES.md` inventories what the hardware can do |
| Offline CFW verification | **`research/verify_cfw.py` passes** — the image we would flash is reproducible from sources we hold, with no Thumb-bit defect |
| Capture corpus | recovered, unfiltered, SHA-pinned in `captures/` |
| **The shell design** | **CLOSED 2026-08-18.** `DESIGN.md`, ~1,850 lines. All six surfaces specified, typography locked, costs **measured** |
| **The build gate** | **`tools/lint.py` + `tools/geometry.py`** — 20 rules, `--selftest` passes, repo exits 0 |
| **The renderer** | **`design/render_shots.py`** — every surface at true 1× 640×480, priced through the firmware's own RLE |

## 🚀 Next

**The feature-creep scope explosion — for the APP layer.** The shell is designed; what the windows
actually *do* is not. Start from **`CAPABILITIES.md`** for what the hardware allows and
**`DESIGN.md` §0 / §4.6** for what the shell already provides and what is ruled out.

Then, per Adam's stated order: *explosion → heavy refinery → consistency passes → a final plan of
the actual implementation → then, slowly, code.*

⚠ **`/home/user/damagewm` is NOT a git repository.** ~5,100 lines of documentation and tooling with
no version control, and "clean repo" is a step in the stated methodology that has not happened.
Worth doing before the app-layer explosion multiplies the page count.

---

## 🔴 First light — the consolidated checklist

Everything below is blocked on being on hardware. Scattered across `DESIGN.md` §10 and
`overview.md` §11; gathered here so nothing is lost.

**Do before anything else**

1. **`python3 research/verify_cfw.py`** — free, offline, proves the image. Re-run before *any*
   flashing conversation.
2. **`g2flash.py --stop-before flash`** — full dry run, writes nothing. Every time.
3. Say out loud that **leaving firmware 2.2.2 is irreversible** (it is not in the public archive).

**Measure on the first session**

| # | what | why it matters |
|---|---|---|
| 1 | **Safe area** — draw a border, shrink until fully visible, store it | `DESIGN.md` §2.2b: 480 vs 288 is a *calibrated setting*, not a design choice |
| 2 | **Per-notch scroll** (graded **C**) | the entire focus model and the fixed-cursor list rest on it |
| 3 | **Comfortable disparity `d`** — ramp 0/4/8/12/16 | and whether stock FAR already spends the budget |
| 4 | **The rect budget of 5** (graded **I**) | derived from `cfw_diag()`, never observed; failure is silent |
| 5 | **Two-arm BTSnoop capture** | settles the bulk-to-LEFT / control-to-RIGHT split (graded **I**) |
| 6 | **CFW ack latency** on the direct-FB path | prices every estimate; a tuning constant, not a gate |
| 7 | **msgId-255 behaviour under CFW** | it kills the link on stock |
| 8 | **Chrome legibility** at 32/28 px bars, real faces on glass | the one thing renders cannot answer |
| 9 | **Whether a normal Android app can see WEA/CMAS alerts** (Pixel 10a) | `DESIGN.md` §4.5 promises emergency alerts; unverified |
| 10 | **Connected RSSI** — obtainable at all, and from which link | the status bar's link cell |
| 11 | **Transport** — PC-direct BLE vs phone-bridged | decides where the BLE stack lives; PC-direct only ever works at the desk |

**Start BTSnoop BEFORE connecting** on any recapture — handle 65's connection setup is the one gap
in the existing corpus.

**Cheap probes nobody has run**

- **The logger service (sid 0x0F)** — would surface the CFW's own `decompress failed` messages.
  Highest-value untested lead; turns silent garbage into a visible error.
- **The file-export service (sid 198/199)** — the only real lead against "no firmware read-back".
  `eErrorCode` has explicit `NOT_SUPPORT`/`SUPPORT`, so the probe is safe.

---

## Open design questions (not hardware-blocked)

- **Where system-state detail lives** — orphaned when the long-press info popup became the switcher.
  Live telemetry is in the status bar; the deeper view wants to be a window, i.e. app-layer work.
- **Per-window typeface for the windows not yet designed** — Files, Calendar, Music, SMS, Timers,
  Scout, Notices all inherit Clear Sans until their app earns an override. Deliberately not invented.
- **Typed-text input** — G2CC had an `onTypedText` path; unported, and the ring alone cannot type.

---

## System changes made for this project

- **`/etc/portage/package.accept_keywords/damage-fonts`** — `~amd64` for nine font *data* packages
  (no code) so the typeface survey could run. Safe to remove; the fonts stay installed.
- **44 `media-fonts/*` packages installed** (450 families, up from ~35). `design/fonts.json` pins
  the 66 candidates that were evaluated. The four locked faces are Clear Sans, Fira Sans, Alegreya
  and JetBrains Mono — **`tools/lint.py` checks glyph coverage against exactly those**, so the table
  at the top of that file must grow if a window ever claims a fifth.
