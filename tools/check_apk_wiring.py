#!/usr/bin/env python3
"""The APK's wiring gate (APK 0.64, HANDOFF.md §75.1): run it after `./gradlew :phone:assembleDebug`.

Context for the reader: a personal device; Damage's own phone app. The uncaught-error record is
installed by `DamageApp`, an `Application` class that Android runs only when the manifest's
`<application android:name>` names it. An APK without the attribute assembles, installs and runs —
with a class nothing calls and no record of an uncaught error. No Kotlin test and no mutation of
`core` can see that (the phone module has no tests), so this reads the build's own outputs.

    ./gradlew :phone:assembleDebug        # its own gradle invocation
    python3 tools/check_apk_wiring.py
    python3 tools/check_apk_wiring.py --selftest      # the gate's own checks, on a scratch root; no gradle

It checks, and prints one line each:
  0. the SOURCE manifest's <application> names it (`.DamageApp`) — what the two build outputs are held
     against, and a text check like 3-7: it needs no build;
  1. the MERGED manifest's <application> names wm.damage.phone.DamageApp;
  2. the debug APK holds that class (its dex files) and that name (its binary manifest);
  3. `DamageApp.onCreate` installs the record;
  4. `ShellService.onCreate` runs, in this order: startForeground, the journal's rotation, the
     pickup of the last run's record, the stack, and only then the last run's line — logged, then raised on
     the glasses as a `process` fault (0.65 P7, shown whatever `Notify · Damage` says); the desktop's
     `runShell` claims the data directory (`InstanceLock.claimOrExit`, R2) and then runs the record, the
     rotation and the pickup before its store, host and journal writer; `hostOnly` claims it before its
     providers, its store and its host (4c);
  5. `Journal.rotateAtStart` and `UncaughtRecord.install` are called from the two process starts and
     nowhere else in main code (the phone's `ShellService.onCreate` / `DamageApp.onCreate`, the
     desktop's `runShell`): a tool entry (`--selfcheck`, `--snapshot`, the `--*-check` entries) or
     `--host-only` must never rename the service's journal; `InstanceLock.claimOrExit` from `runShell`
     and `hostOnly` only (a tool that claimed the directory would exit while the service runs);
  6. (0.64 P4) the low glasses battery reaches the phone: `ShellService.startStack` sets the shell's
     `onGlassesBattery` hook — it shows and withdraws the notification and is not behind `Phone
     notifications` — `channels()` creates the "Glasses battery" channel at the default importance, and
     `batteryNotification` posts under the fixed id on it, with a big-text style (R2) so its text is not cut
     to one line. A shell whose hook nothing sets builds and runs.
  7. (0.64 P4) the dev probes reach the shell: both hosts' probe routes (`ShellService`'s and the desktop's
     replica `onProbe`) offer a probe to `Shell.devProbe` first and to the transport only when the shell
     says it is not its own — `rest=on|off` is the shell's, and a route that skips the shell builds and
     runs and answers `rest` with "not a probe this transport runs".
Checks 3-7 read the sources as text: they catch a line removed or moved, not a wrong meaning. The
text is read with its comments blanked by one pass that knows a string from a comment (`blank_comments`).

A build output OLDER than the source manifest is judged by content, not refused by its time: when the
source manifest and the output both name the application the check is made and a `note` line says the
output is older (a manifest rewritten with the same bytes leaves the build up to date — gradle
decides by content and would rewrite nothing); when they differ it is not judged.
A file the gate cannot read is a line, never a Python fault (R2): no source manifest, or one that is not
XML, is a FAIL and nothing is held against it; an APK with no AndroidManifest.xml in it FAILs the manifest
line; a merged manifest that is not XML, or an APK that is not a zip, is not judged.

Exit status: 0 every check holds; 1 a check failed; 2 not judged — no build output, one that cannot be
read, or one older than the source manifest that does not agree with it (assemble first;
`--rerun-tasks` if that rewrites nothing).
"""
import glob, os, re, sys, zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = 'wm.damage.phone.DamageApp'
ANDROID = '{http://schemas.android.com/apk/res/android}'
MAIN_SOURCES = ('core/src/main', 'desktop/src/main', 'phone/src/main')


# What can change how Kotlin text is read, per place: in code, in a "…" string, in a raw (three-quote) string,
# in a comment.
_IN_CODE = re.compile(r'//|/\*|"""|"|\'|`|[{}]')
_IN_STRING = re.compile(r'\\.|\$\{|"|\n')
_IN_RAW_STRING = re.compile(r'\$\{|"""+')
_IN_COMMENT = re.compile(r'/\*|\*/')
_blanked = {}                   # by the text itself — never by a file's time and size, which two texts can share


def blank_comments(s):
    """Kotlin text with its comments blanked: same length and line breaks, so offsets and line numbers hold.
    ONE pass that knows where it is (RF-7). The two regular expressions this replaces took `//` and `/*` for a
    comment wherever they stood: `"*/*"` opened one that ran to the end of the NEXT comment, and
    `"http://x"; call()` lost the call — code the checks below then could not see. So: a `"…"` string (with
    its backslash escapes) and a raw, three-quote string hold no comment; a `${…}` inside either is code
    again, with strings and braces of its own; a quote in a character literal opens no string, and neither
    a quote nor two slashes in a name in backticks open anything; block comments nest. Strings stay as they
    are — the checks look for some. A `"…"` string never passes a line end here, so a text no compiler would
    take cannot hide more than the rest of one line.
    Not read: a `$$"…"` string, where `${` is plain text (none in this code base)."""
    out, done, i, n = [], 0, 0, len(s)
    string = None               # None: in code; else the quote of the string being read, '"' or '"""'
    templates = []              # one per `${` being read: [the quote of its string, `{` open inside it]
    while i < n:
        if string is None:
            m = _IN_CODE.search(s, i)
            if m is None:
                break
            t, i = m.group(), m.end()
            if t in ('//', '/*'):
                if t == '//':
                    i = s.find('\n', i)
                    i = n if i < 0 else i
                else:
                    depth = 1
                    while depth:
                        c = _IN_COMMENT.search(s, i)
                        if c is None:
                            i = n
                            break
                        depth += 1 if c.group() == '/*' else -1
                        i = c.end()
                out += [s[done:m.start()], re.sub(r'[^\n]', ' ', s[m.start():i])]
                done = i
            elif t in ('"', '"""'):
                string = t
            elif t == "'":
                # a character literal — 'x', '"', '\n', '\'', '\u0041': step over it, its quote included
                close = s.find("'", i + 2, i + 8) if s.startswith('\\', i) else i + 1
                if 0 <= close < n and s[close] == "'":
                    i = close + 1
            elif t == '`':
                # a name in backticks — `is`, a test's `it says "x"`: step over it; it never passes a line end
                close, line_end = s.find('`', i), s.find('\n', i)
                if close >= 0 and (line_end < 0 or close < line_end):
                    i = close + 1
            elif templates:
                if t == '{':
                    templates[-1][1] += 1
                elif templates[-1][1]:
                    templates[-1][1] -= 1
                else:
                    string = templates.pop()[0]         # the `}` of its `${`: the string goes on
        else:
            m = (_IN_STRING if string == '"' else _IN_RAW_STRING).search(s, i)
            if m is None:
                break
            t, i = m.group(), m.end()
            if t == '${':
                templates.append([string, 0])
                string = None
            elif t[0] in '"\n':
                string = None                           # its closing quote(s) — or, for "…", the line's end
    return ''.join(out) + s[done:]


def kotlin_code(path):
    """A Kotlin file with its comments blanked (`blank_comments`)."""
    s = open(path, encoding='utf-8').read()
    if s not in _blanked:
        _blanked[s] = blank_comments(s)
    return _blanked[s]


def application_name(manifest):
    """The `android:name` of a text manifest's <application>, or None. Raises `ET.ParseError` for a file that
    is not XML (the caller says it as a line)."""
    app = ET.parse(manifest).getroot().find('application')
    return None if app is None else app.get(ANDROID + 'name')


def body_of(code, signature):
    """The text of the function whose declaration starts with `signature`, up to its closing brace
    (found by its indentation: the next line that holds only that indentation and `}`)."""
    i = code.find(signature)
    if i < 0:
        return None
    indent = code[code.rfind('\n', 0, i) + 1:i]
    indent = indent[:len(indent) - len(indent.lstrip())]
    end = code.find('\n' + indent + '}', i)
    return code[i:end] if end >= 0 else None


def callers(root, owner, name):
    """[(file relative to root, the function the use is in)] of every use of `owner.name` in main
    sources — and of a bare `name(` inside owner's own file, where it needs no qualifier.
    Outside the owner's file the name is reached through the owner, so every form of that counts:
    `Owner.name(`, the same behind its package (`wm.damage.core.comp.Owner.name(` — this code base
    writes many calls that way, and the first form of this check did not see one: 0.64 P3 verify),
    a reference (`Owner::name`), and an import of it (the call is then bare; the import line is
    not, and it sits in no function, so it never equals an expected caller)."""
    out = []
    for src in MAIN_SOURCES:
        for path in sorted(glob.glob(os.path.join(root, src, '**', '*.kt'), recursive=True)):
            code = kotlin_code(path)
            own = os.path.basename(path) == owner + '.kt'
            pattern = (r'(?<![\w.])(?:%s\.)?%s\(' if own else r'(?<!\w)%s(?:\.Companion)?(?:\.|::)%s\b') % (owner, name)
            for m in re.finditer(pattern, code):
                if code[:m.start()].endswith('fun '):
                    continue                                   # its own declaration
                fun = re.findall(r'^[ \t]*(?:(?:private|internal|override|suspend|public) )*fun (\w+)', code[:m.start()], re.M)
                out.append((os.path.relpath(path, root), fun[-1] if fun else '?'))
    return sorted(out)


def check(root=ROOT, say=print):
    """Runs every check on the tree at `root`. Returns the exit status."""
    failed, unjudged, notes = [], [], []

    def line(ok, what, why=''):
        say('%s  %s%s' % ('ok  ' if ok else 'FAIL', what, (': ' + why) if why and not ok else ''))
        if not ok:
            failed.append(what)

    src_manifest = os.path.join(root, 'phone/src/main/AndroidManifest.xml')

    # 0. the source manifest: what a build output is held against (and a text check: it needs no build).
    #    Missing or not XML is a FAIL line, and nothing is held against it (R2: both ended as a Python fault)
    try:
        src_time = os.path.getmtime(src_manifest)
        src_name = application_name(src_manifest)
        src_why = 'android:name is %r in phone/src/main/AndroidManifest.xml' % src_name
    except (OSError, ET.ParseError) as e:
        src_time, src_name = None, None
        src_why = 'phone/src/main/AndroidManifest.xml cannot be read (%s: %s)' % (type(e).__name__, e)
    src_names = src_name in (APP, '.' + APP.rsplit('.', 1)[1])
    line(src_names, 'the source manifest names %s as the application' % APP, src_why)

    def stale(what, path, agrees):
        """An output older than the source manifest is judged by CONTENT, not refused by time (RF-7). Gradle
        decides by content: after the manifest is rewritten with the same bytes (a branch switched and
        switched back) the build is up to date and rewrites nothing, so "run the build" could never clear a
        refusal by time. Both name the application → judged, with a note. They differ → not judged (True).
        With no readable source manifest there is nothing to hold the output against: not judged (True)."""
        if src_time is None:
            unjudged.append('%s: no readable source manifest to hold it against (the FAIL above)' % what)
            return True
        if os.path.getmtime(path) >= src_time:
            return False
        if agrees:
            notes.append('%s is older than phone/src/main/AndroidManifest.xml; both name the application, so it is judged '
                         '(a manifest rewritten with the same bytes leaves the build up to date)' % what)
            return False
        unjudged.append('%s is older than phone/src/main/AndroidManifest.xml and the two do not agree on the application '
                        '(run :phone:assembleDebug; add --rerun-tasks if that rewrites nothing)' % what)
        return True

    # 1. the merged manifest
    merged = sorted(glob.glob(os.path.join(root, 'phone/build/intermediates/merged_manifests/debug/**/AndroidManifest.xml'), recursive=True))
    if not merged:
        unjudged.append('no merged manifest under phone/build/intermediates/merged_manifests/debug (run :phone:assembleDebug)')
    else:
        try:
            name = application_name(merged[0])
        except ET.ParseError as e:
            # a build output that cannot be read is not judged (R2: it ended as a Python fault)
            unjudged.append('the merged manifest %s is not XML (%s): run :phone:assembleDebug'
                            % (os.path.relpath(merged[0], root), e))
            merged = None
        if merged and not stale('the merged manifest', merged[0], src_names and name == APP):
            line(name == APP, 'the merged manifest names %s as the application' % APP,
                 'android:name is %r in %s' % (name, os.path.relpath(merged[0], root)))

    # 2. the APK itself
    apk = os.path.join(root, 'phone/build/outputs/apk/debug/phone-debug.apk')
    if not os.path.isfile(apk):
        unjudged.append('no phone/build/outputs/apk/debug/phone-debug.apk (run :phone:assembleDebug)')
    else:
        try:
            with zipfile.ZipFile(apk) as z:
                descriptor = ('L' + APP.replace('.', '/') + ';').encode()
                in_dex = any(descriptor in z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n))
                man = z.read('AndroidManifest.xml') if 'AndroidManifest.xml' in z.namelist() else None
        except zipfile.BadZipFile as e:
            # a build output that cannot be read is not judged (R2: it ended as a Python fault)
            unjudged.append('phone/build/outputs/apk/debug/phone-debug.apk is not a zip (%s): run :phone:assembleDebug' % e)
            in_dex = man = None
        else:
            in_manifest = man is not None and (APP.encode('utf-16-le') in man or APP.encode('utf-8') in man)
            # an APK with no manifest in it (R2: a KeyError before) is judged: its manifest holds no name
            if not stale('the APK', apk, src_names and in_manifest):
                line(in_dex, 'the APK holds the class %s' % APP, 'no dex file holds its descriptor')
                line(in_manifest, 'the APK\'s manifest holds the name %s' % APP,
                     'the APK holds no AndroidManifest.xml' if man is None else 'the binary manifest does not hold the string')

    # 3. DamageApp installs the record
    app_kt = os.path.join(root, 'phone/src/main/kotlin/wm/damage/phone/DamageApp.kt')
    on_create = body_of(kotlin_code(app_kt), 'override fun onCreate()') if os.path.isfile(app_kt) else None
    line(on_create is not None and 'UncaughtRecord.install(' in on_create and 'super.onCreate()' in on_create,
         'DamageApp.onCreate installs the uncaught-error record', 'no UncaughtRecord.install( in its onCreate')

    # 4. the order in ShellService.onCreate
    svc = body_of(kotlin_code(os.path.join(root, 'phone/src/main/kotlin/wm/damage/phone/ShellService.kt')), 'override fun onCreate()')
    steps = ['Log.addSink(', 'startForeground(', 'Journal.rotateAtStart(', 'Journal.archivesSaid(', 'UncaughtRecord.installedLine(',
             'UncaughtRecord.takePending(', 'startStack(', 'Log.e("uncaught", it)', 'raiseHostFault("process", it)']
    at = [svc.find(s) if svc else -1 for s in steps]
    missing = [s for s, i in zip(steps, at) if i < 0]
    line(not missing and at == sorted(at), 'ShellService.onCreate: sink, foreground, rotation, the archives counted for the process note, record in place, '
         'pickup, stack, then the last run\'s line (logged, then raised as a `process` fault)',
         ('missing ' + ', '.join(missing)) if missing else 'out of order: ' + ' < '.join(s for _, s in sorted(zip(at, steps))))

    # 4b. the same order at the desktop's start, ahead of the store and the content host — and FIRST the claim of
    #     the data directory (R2, RE-3): a second process on it would rename the service's journal and take its
    #     pending record in the steps that follow
    main_kt = kotlin_code(os.path.join(root, 'desktop/src/main/kotlin/wm/damage/desktop/Main.kt'))
    run = body_of(main_kt, 'private fun runShell(')
    steps = ['InstanceLock.claimOrExit(', 'UncaughtRecord.install(', 'Journal.rotateAtStart(', 'Journal.archivesSaid(', 'UncaughtRecord.installedLine(',
             'UncaughtRecord.takePending(', 'Persistence(', 'ContentHostServer(', 'Journal(']
    at = [run.find(s) if run else -1 for s in steps]
    missing = [s for s, i in zip(steps, at) if i < 0]
    line(not missing and at == sorted(at), 'runShell: the data directory claimed, then record, rotation, the archives counted, record in place, pickup — '
         'before the store, the host and any journal writer',
         ('missing ' + ', '.join(missing)) if missing else 'out of order: ' + ' < '.join(s for _, s in sorted(zip(at, steps))))

    # 4c. --host-only claims the directory before its providers, its store and its host (the same store, ports, engines)
    host = body_of(main_kt, 'private suspend fun hostOnly(')
    steps = ['InstanceLock.claimOrExit(', 'LocalTmuxProvider(', 'Persistence(', 'startMusic(', 'ContentHostServer(']
    at = [host.find(s) if host else -1 for s in steps]
    missing = [s for s, i in zip(steps, at) if i < 0]
    line(not missing and at == sorted(at), 'hostOnly: the data directory claimed before its providers, its store and its host',
         ('missing ' + ', '.join(missing)) if missing else 'out of order: ' + ' < '.join(s for _, s in sorted(zip(at, steps))))

    # 5. who calls the two start-only functions
    want = {('Journal', 'rotateAtStart'): [('desktop/src/main/kotlin/wm/damage/desktop/Main.kt', 'runShell'),
                                           ('phone/src/main/kotlin/wm/damage/phone/ShellService.kt', 'onCreate')],
            ('UncaughtRecord', 'install'): [('desktop/src/main/kotlin/wm/damage/desktop/Main.kt', 'runShell'),
                                            ('phone/src/main/kotlin/wm/damage/phone/DamageApp.kt', 'onCreate')],
            # R2 (RE-3): the PC's two starts on a data directory, and never a tool — a tool that claimed it would
            # exit while the service runs
            ('InstanceLock', 'claimOrExit'): [('desktop/src/main/kotlin/wm/damage/desktop/Main.kt', 'hostOnly'),
                                              ('desktop/src/main/kotlin/wm/damage/desktop/Main.kt', 'runShell')]}
    for (owner, name), expect in want.items():
        got = callers(root, owner, name)
        line(got == sorted(expect), '%s.%s is called from the %s only' % (owner, name,
             'two PC starts (runShell, hostOnly)' if owner == 'InstanceLock' else 'two process starts'),
             'called from ' + (', '.join('%s (%s)' % c for c in got) or 'nowhere'))

    # 6. the low-battery notification (0.64 P4): the shell's latch reaches the phone through a hook only
    #    startStack can set, on a channel only channels() can create — a shell with no hook builds and runs
    svc_code = kotlin_code(os.path.join(root, 'phone/src/main/kotlin/wm/damage/phone/ShellService.kt'))
    stack = body_of(svc_code, 'private fun startStack(') or ''
    i = stack.find('sh.onGlassesBattery = {')
    hook = stack[i:stack.find('\n        }', i)] if i >= 0 else ''
    line(bool(hook) and 'batteryNotification(' in hook and '.cancel(BATTERY_NOTIF_ID)' in hook and 'phoneNotifications' not in hook,
         'ShellService.startStack sets the low-battery hook: shown and withdrawn, and not behind `Phone notifications`',
         'no sh.onGlassesBattery = { … } in startStack' if not hook else 'the hook is ' + ' '.join(hook.split()))
    chans = body_of(svc_code, 'private fun channels()') or ''
    line('NotificationChannel(CHANNEL_BATTERY, "Glasses battery", NotificationManager.IMPORTANCE_DEFAULT)' in chans,
         'channels() creates the Glasses battery channel at the default importance',
         'no NotificationChannel(CHANNEL_BATTERY, "Glasses battery", NotificationManager.IMPORTANCE_DEFAULT) in channels()')
    shown = body_of(svc_code, 'private fun batteryNotification(') or ''
    line('.notify(BATTERY_NOTIF_ID' in shown and 'NotificationCompat.Builder(this, CHANNEL_BATTERY)' in shown,
         'batteryNotification posts under the fixed id on that channel',
         'no .notify(BATTERY_NOTIF_ID … Builder(this, CHANNEL_BATTERY) in batteryNotification')
    # R2 (RG-7): with no big-text style a collapsed notification shows one line and cuts the rest, and there is
    # nothing to expand — its sibling `urgentNotification` sets the style beside the same call
    line('BigTextStyle' in shown,
         'batteryNotification sets a big-text style, so its text is never cut to one line',
         'no BigTextStyle in batteryNotification')

    # 7. the dev probes (0.64 P4): `rest=on|off` is the SHELL's; both hosts offer a probe to it first
    for path, to_shell, to_transport in (
            ('phone/src/main/kotlin/wm/damage/phone/ShellService.kt', 'shell?.devProbe(n, v)', 'transport?.devProbe(n, v)'),
            ('desktop/src/main/kotlin/wm/damage/desktop/Main.kt', 'it.shell.devProbe(n, v)', 'it.transport.devProbe(n, v)')):
        code = kotlin_code(os.path.join(root, path))
        i = code.find('onProbe = {')
        route = code[i:code.find('\n', code.find('Log.w(', i))] if i >= 0 else ''
        a, b = route.find(to_shell), route.find(to_transport)
        line(0 <= a < b, '%s: the probe route offers a probe to the shell, then to the transport' % os.path.basename(path),
             'no onProbe = { … } route' if not route else 'the route is ' + ' '.join(route.split()))

    for n in notes:
        say('note  ' + n)
    for u in unjudged:
        say('NOT JUDGED  ' + u)
    return 1 if failed else 2 if unjudged else 0


def selftest():
    """The gate's own checks, on a scratch root: a COPY of this tree's main Kotlin sources and source manifest,
    with stand-in build outputs (a merged manifest, a zip that holds what the gate reads of an APK). No gradle
    runs and nothing under the repository is written. The cases stand on this tree's sources, so the first one
    fails when the tree's own text checks do — mend the tree, then run this again."""
    import shutil, tempfile
    root = tempfile.mkdtemp(prefix='apk-wiring-selftest-')
    T = 1794000000                                           # a fixed time for the outputs; nothing reads the clock
    manifest = os.path.join(root, 'phone/src/main/AndroidManifest.xml')
    merged = os.path.join(root, 'phone/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml')
    apk = os.path.join(root, 'phone/build/outputs/apk/debug/phone-debug.apk')
    tool = os.path.join(root, 'core/src/main/kotlin/wm/damage/core/util/SelftestTool.kt')
    scrap = os.path.join(root, 'Snippet.kt')
    missed, cases = 0, 0

    def outputs(named=True, at=T):
        """The stand-in build outputs, with or without the application's name in them, last written at `at`."""
        for f in (merged, apk):
            os.makedirs(os.path.dirname(f), exist_ok=True)
        open(merged, 'w').write('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application%s/></manifest>'
                                % (' android:name="%s"' % APP if named else ''))
        with zipfile.ZipFile(apk, 'w') as z:
            z.writestr('classes.dex', b'..' + (('L' + APP.replace('.', '/') + ';').encode() if named else b'') + b'..')
            z.writestr('AndroidManifest.xml', (APP if named else 'wm.damage.phone.MainActivity').encode('utf-16-le'))
        for f in (merged, apk):
            os.utime(f, (at, at))

    def source(text, at):
        open(manifest, 'w').write(text)
        os.utime(manifest, (at, at))

    def manifest_naming(value):
        """This tree's source manifest with its <application android:name> set to `value` (None: taken out)."""
        text, n = re.subn(r'(<application\b[^>]*?)\s+android:name="[^"]*"',
                          lambda m: m.group(1) + ('' if value is None else ' android:name="%s"' % value), real, count=1, flags=re.S)
        assert n == 1, 'the source manifest\'s <application> has no android:name to stand in for'
        return text

    def run():
        out = []
        return check(root=root, say=out.append), out

    def case(title, notes):
        nonlocal missed, cases
        cases += 1
        missed += bool(notes)
        print('%s  %s%s' % ('FAIL' if notes else 'ok  ', title, ''.join('\n        ' + n for n in notes)))

    def said(out, *starts):
        """The lines of `out` that open with one of `starts`."""
        return [l for l in out if l.startswith(starts)]

    def tool_with(line):
        """A main source file no check expects, whose one function makes the call `line` holds."""
        open(tool, 'w').write('package wm.damage.core.util\n\nimport wm.damage.core.comp.Journal\n\nobject SelftestTool {\n'
                              '    fun run(p: java.nio.file.Path) {\n%s\n    }\n\n    /** a later comment */\n    fun other() {}\n}\n' % line)

    try:
        for src in MAIN_SOURCES:
            for path in glob.glob(os.path.join(ROOT, src, '**', '*.kt'), recursive=True):
                to = os.path.join(root, os.path.relpath(path, ROOT))
                os.makedirs(os.path.dirname(to), exist_ok=True)
                shutil.copyfile(path, to)
        real = open(os.path.join(ROOT, 'phone/src/main/AndroidManifest.xml'), encoding='utf-8').read()
        os.makedirs(os.path.dirname(manifest), exist_ok=True)
        rotate = 'Journal.rotateAtStart is called from the two process starts only'
        seen = 'FAIL  %s: called from core/src/main/kotlin/wm/damage/core/util/SelftestTool.kt (run), ' % rotate

        source(real, T - 3600)
        outputs()
        code, out = run()
        case('outputs newer than the source manifest: every check holds (this tree\'s own sources, stand-in outputs)',
             [n for n in ['exit %d, not 0' % code if code else ''] if n] + ['said: ' + l for l in out if not l.startswith('ok  ')])

        source(manifest_naming(APP), T - 3600)
        code, out = run()
        case('...and with the name written in full in the source manifest',
             [n for n in ['the stand-in source manifest does not hold the full name' if application_name(manifest) != APP else '',
                          'exit %d, not 0' % code if code else ''] if n] + ['said: ' + l for l in out if not l.startswith('ok  ')])

        source(real, T + 5)
        code, out = run()
        build = said(out, 'ok    the merged manifest', 'ok    the APK')
        case('RF-7: the source manifest\'s time moved and its bytes did not: the outputs are judged, with a note; '
             'the answer is not "run the build"',
             [n for n in ['exit %d, not 0' % code if code else '',
                          'the three build lines are not all ok: %r' % build if len(build) != 3 else '',
                          'no note of the older outputs: %r' % said(out, 'note') if len(said(out, 'note')) != 2 else ''] if n]
             + ['said: ' + l for l in said(out, 'NOT JUDGED', 'FAIL')])

        outputs(named=False)
        code, out = run()
        refused = said(out, 'NOT JUDGED')
        case('...older outputs that do NOT name what the source names are not judged, and the line says how they get rewritten',
             [n for n in ['exit %d, not 2' % code if code != 2 else '',
                          'said %r' % refused if len(refused) != 2 or not all('--rerun-tasks' in l for l in refused) else '']
              if n] + ['said: ' + l for l in said(out, 'FAIL')])

        source(real, T - 3600)
        code, out = run()
        case('outputs newer than the source manifest that do not name the application FAIL (what the gate is for)',
             [n for n in ['exit %d, not 1' % code if code != 1 else '',
                          'said %r' % said(out, 'FAIL') if len(said(out, 'FAIL  the merged manifest', 'FAIL  the APK')) != 3 else ''] if n]
             + ['said: ' + l for l in said(out, 'NOT JUDGED')])

        source(manifest_naming(None), T - 3600)
        outputs()
        code, out = run()
        source(manifest_naming(None), T + 5)
        code2, out2 = run()
        case('the SOURCE manifest without the name is a FAIL of its own; outputs older than it that hold the name are not judged',
             [n for n in ['the stand-in source manifest still names it' if application_name(manifest) is not None else '',
                          'newer outputs: exit %d, not 1' % code if code != 1 else '',
                          'newer outputs: said %r' % said(out, 'FAIL') if not said(out, 'FAIL  the source manifest names') else '',
                          'older outputs: exit %d, not 1' % code2 if code2 != 1 else '',
                          'older outputs: said %r' % out2[:1] if not said(out2, 'FAIL  the source manifest names') else '',
                          'older outputs were judged: %r' % (said(out2, 'ok    the merged', 'ok    the APK', 'note') + said(out2, 'NOT JUDGED'))
                          if said(out2, 'ok    the merged', 'ok    the APK', 'note') or len(said(out2, 'NOT JUDGED')) != 2 else ''] if n])

        # R2 item 29 (c): three older arms ended as a Python fault (R2t-verify §3c) — each is a line and the
        # gate's own exit status now; a fourth of the class (an APK that is not a zip) with them
        def guarded():
            try:
                return run()
            except Exception as e:
                return 'raised %s' % type(e).__name__, []
        source(real, T - 3600)
        outputs()
        os.remove(manifest)
        code, out = guarded()
        case('R2: no SOURCE manifest is a FAIL line (exit 1), and the outputs are not judged against nothing',
             [n for n in ['exit %r, not 1' % (code,) if code != 1 else '',
                          'said %r' % out[:2] if not said(out, 'FAIL  the source manifest') else '',
                          'an output was judged: %r' % said(out, 'ok    the merged', 'ok    the APK') if said(out, 'ok    the merged', 'ok    the APK') else ''] if n])
        source(real, T - 3600)
        with zipfile.ZipFile(apk, 'w') as z:
            z.writestr('classes.dex', b'..' + ('L' + APP.replace('.', '/') + ';').encode() + b'..')
        os.utime(apk, (T, T))
        code, out = guarded()
        case('R2: an APK with no AndroidManifest.xml in it is a FAIL line (exit 1)',
             [n for n in ['exit %r, not 1' % (code,) if code != 1 else '',
                          'said %r' % said(out, 'FAIL', 'NOT JUDGED') if not said(out, 'FAIL  the APK\'s manifest') else ''] if n])
        open(apk, 'wb').write(b'not a zip at all')
        os.utime(apk, (T, T))
        code, out = guarded()
        case('R2: an APK that is not a zip is NOT JUDGED (exit 2), with a line',
             [n for n in ['exit %r, not 2' % (code,) if code != 2 else '',
                          'said %r' % said(out, 'FAIL', 'NOT JUDGED') if not [l for l in said(out, 'NOT JUDGED') if 'phone-debug.apk' in l] else ''] if n])
        outputs()
        open(merged, 'w').write('<manifest><application')
        os.utime(merged, (T, T))
        code, out = guarded()
        case('R2: a merged manifest that is not XML is NOT JUDGED (exit 2), with a line',
             [n for n in ['exit %r, not 2' % (code,) if code != 2 else '',
                          'said %r' % said(out, 'FAIL', 'NOT JUDGED') if not [l for l in said(out, 'NOT JUDGED') if 'merged manifest' in l and 'XML' in l] else ''] if n])
        outputs()

        source(real, T - 3600)
        tool_with('        val accept = "*/*"\n        Journal.rotateAtStart(p)')
        code, out = run()
        case('RF-7: a call after a "*/*" literal is seen (a comment opener inside a string opens no comment)',
             [n for n in ['exit %d, not 1' % code if code != 1 else '', 'said %r' % said(out, 'FAIL') if not said(out, seen) else ''] if n])

        tool_with('        val accept = "any"\n        Journal.rotateAtStart(p)')
        code, out = run()
        case('...the same file without the literal is seen (the case above fails for the literal, not for the file)',
             [n for n in ['exit %d, not 1' % code if code != 1 else '', 'said %r' % said(out, 'FAIL') if not said(out, seen) else ''] if n])

        tool_with('        val u = "http://x"; Journal.rotateAtStart(p)')
        code, out = run()
        case('RF-7: a call on one line after a "http://…" literal is seen (two slashes inside a string open no comment)',
             [n for n in ['exit %d, not 1' % code if code != 1 else '', 'said %r' % said(out, 'FAIL') if not said(out, seen) else ''] if n])
        os.remove(tool)

        # the reader of Kotlin text itself: what must stay, what must be blanked, and nothing may move
        q3 = '"' * 3
        for title, text, kept, blanked in (
                ('a line comment and a block comment are blanked; a string that holds their openers is not',
                 'val a = "x // y /* z"  // gone1\nval b = 1 /* gone2\n gone3 */ + call()\n', ['"x // y /* z"', 'call()'], ['gone1', 'gone2', 'gone3']),
                ('an escaped quote does not end a string', 'val a = "x\\"// y"; call()  // gone\n', ['"x\\"// y"', 'call()'], ['gone']),
                ('a quote in a character literal opens no string', "val q = '\"'; call() // gone1\nval e = '\\''; more() /* gone2 */\n",
                 ['call()', 'more()'], ['gone1', 'gone2']),
                ('a raw string holds quotes, slashes and lines', 'val r = %s a "b" // c\n /* d */ %s; call() // gone\n' % (q3, q3),
                 ['a "b" // c', '/* d */', 'call()'], ['gone']),
                ('a template is code: a string inside it is a string, and the outer string goes on after it',
                 'val t = "a ${f("b // c")} // d"; call() // gone\n', ['"b // c"', '// d"', 'call()'], ['gone']),
                ('block comments nest', 'val n = 1 /* gone1 /* gone2 */ gone3 */ + call()\n', ['call()'], ['gone1', 'gone2', 'gone3']),
                ('a raw string whose text ends with a quote closes at the last three of the four (Fetchers.kt holds one)',
                 'val re = Regex(%s"(x)"%s); call() // gone\nval s = "kept // too"\n' % (q3, q3), ['call()', '"kept // too"'], ['gone']),
                ('braces inside a template belong to it: the template goes on after a lambda\'s closing brace',
                 'val t = "a ${f { 1 } + "x // y"} // d"; call() // gone\n', ['"x // y"', '// d"', 'call()'], ['gone']),
                ('a "…" string never passes a line end, whatever the line before left open',
                 'val a = "left open\nval b = call() // gone\n', ['call()'], ['gone']),
                ('a quote inside a comment opens no string', '// it said "hello\nval c = call() /* gone "x */ + more()\n', ['call()', 'more()'], ['hello', 'gone']),
                ('a template in a raw string is code too: a comment inside it is one',
                 'val t = %sa ${\n    f() // gone1\n} // kept%s; call() // gone2\n' % (q3, q3), ['// kept', 'call()'], ['gone1', 'gone2']),
                ('a name in backticks holds no string and no comment',
                 'fun `it says "x`() = call() // gone1\nfun `a // b`() = more() // gone2\nval s = "kept // too"\n',
                 ['`it says "x`', 'call()', '`a // b`', 'more()', '"kept // too"'], ['gone1', 'gone2']),
                ('a backtick left open never passes a line end',
                 'val a = `open\nval b = call() // gone\nfun `later`() = more()\n', ['call()', 'more()'], ['gone'])):
            open(scrap, 'w').write(text)
            got = kotlin_code(scrap)
            breaks = lambda t: [i for i, c in enumerate(t) if c == '\n']
            case('reading Kotlin: ' + title,
                 ['its length or a line break moved'][:len(got) != len(text) or breaks(got) != breaks(text)]
                 + ['%r was blanked' % k for k in kept if k not in got] + ['%r was kept' % b for b in blanked if b in got])
    finally:
        shutil.rmtree(root, ignore_errors=True)
    print('selftest: %d of %d' % (cases - missed, cases))
    print('not covered here: a real build\'s outputs (run the gate after :phone:assembleDebug), and a Kotlin text no compiler would take')
    return 1 if missed else 0


if __name__ == '__main__':
    if sys.argv[1:] == ['--selftest']:
        sys.exit(selftest())
    if sys.argv[1:]:
        sys.exit(__doc__)
    sys.exit(check())
