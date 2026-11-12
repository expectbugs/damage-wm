#!/usr/bin/env python3
"""The mutation runner for Damage's core (HANDOFF.md §73): apply one source mutation at a time, run the test
classes the mutation names, report which tests fail, restore the file.

    python3 research/mutate_core.py research/mutations-73.json --check        # do the edits still apply? no build
    python3 research/mutate_core.py research/mutations-73.json                # the whole list (hours)
    python3 research/mutate_core.py research/mutations-73.json M1-no-promotion L1-not-isolated-counted
    python3 research/mutate_core.py research/mutations-75-p1.json --why        # ...and why each expected test failed
    python3 research/mutate_core.py --selftest                                # the runner's own checks; no gradle

A list entry: {"id", "file" (under core/src/main/kotlin/wm/damage/core/), "edits": [[old, new], …] (each `old`
must occur exactly once), "tests": gradle --tests patterns, "expect": the test names that must fail}.
CAUGHT = every expected test failed under the mutation. NOT CAUGHT BY = an expected test ran and passed.
NOT RUN = an expected test has no result in the run (renamed since, or outside the entry's `tests` patterns):
nothing is known about the guard and the entry is not judged. NO PIN (declared) = the entry's `expect` is a
sentence that opens with `<` or `(` — the list's own statement that no test pins the guard — and the run only
shows what failed beside it. `--why` prints the first line of each expected test's failure under it: a pin is
watched to fail FOR ITS STATED REASON, and a test that fails because the mutated tree faults somewhere else is
not watching its guard.

It changes a source file while it runs: run nothing else on the tree meanwhile. Before the mutant is placed the
original gets a second name beside it, `<file>.mutate-orig` (a hard link: the file itself, whole); the mutant
takes the source's name by one rename and the put-back is one rename, in a `finally` — the source is then the
same file again, time and all, and is never cut or empty on the way. SIGTERM and SIGHUP end the gradle client
and run that `finally` (exit status 128 + the signal; what was judged before is printed), as Ctrl-C does; a
SIGHUP that was ignored at the start (`nohup`) stays ignored; either of the two arriving when the list has
already ended stops nothing — the report is printed whole and a line says it came. SIGKILL and a power loss
run nothing: the mutant stays in the source and `git status` lists `<file>.mutate-orig` beside it. The NEXT
start of this tool on the tree, whatever its arguments, puts every such file back first and says so on stderr;
what the source held is kept as `<file>.mutate-left` (the mutant — or the mutant with an edit made on top of it
since, which is why it is kept; remove it once looked at) — unless it already holds the original's bytes (put
right by hand since): then nothing is kept. One run per tree: a run holds a lock on the source
directory (no lock file; it ends with the process), and a second start while it lives puts nothing back and
runs nothing (exit 2).

A verdict is read from THIS run's result files and gradle's exit status (0.64 T0, green-gate GG-7). The result
files of the run before are removed here before gradle starts: gradle's own `:core:cleanTest` removes them only
when gradle gets that far, and a gradle that ended earlier (a second gradle on the tree ending on the lock
wait, a daemon that could not start) left the last mutant's files to be read as this one's — a CAUGHT with no
test run behind it. `ERROR (gradle exit N, no results)` = no testcase in a result file after the run (a
`--tests` pattern that matches no class reads so too); `ERROR (gradle exit N, no testcase failed)` = gradle
failed and no test did. Neither is a verdict on the guard; the line under it is what gradle said.
`ran N` is the number of testcases this run wrote. Results are keyed `Class.name`; an expected name may be
written bare (as the lists do) or as `Class.name`, and a bare name that two classes of the run both hold is
caught only when it failed in both.

Exit status: 0 every entry was judged (NOT CAUGHT and NO PIN are results, and they are printed); 1 an entry
could not be judged (ERROR, EDIT DID NOT APPLY, DID NOT COMPILE, NOT RUN), a self-test case missed, or no list
was named (this text); 2 the command line was not understood (an unknown `--flag`, an id the list does not
hold) or another run of this tool holds the tree, and nothing ran; 143 / 129 ended by SIGTERM / SIGHUP with
the source put back.
The fork's own sweep is ~/damage-cfw/tools/mutate.py; this one is the phone-side twin."""
import subprocess, sys, re, glob, os, json, html, fcntl, signal, filecmp

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = 'core/src/main/kotlin/wm/damage/core/'
RESULTS = 'core/build/test-results/test/TEST-*.xml'
PKG = 'wm.damage.core.'
FLAGS = ('--check', '--why', '--selftest')
NOT_JUDGED = ('ERROR', 'EDIT DID NOT APPLY', 'DID NOT COMPILE', 'NOT RUN')
# The names a run puts beside a source file. None is a `*.kt` name: the Kotlin source set, tools/lint.py and
# tools/check_apk_wiring.py all read `*.kt`, and none of them may take one of these for a source.
BACKUP, TEMP, LEFT = '.mutate-orig', '.mutate-tmp', '.mutate-left'
SIGNALS = (signal.SIGTERM, signal.SIGHUP)


def claim(root):
    """The lock of the tree at `root` for this process: an open descriptor of the source directory holding
    an exclusive `flock`, or None when another run of this tool holds it. There is no lock file — the kernel
    drops the lock at ANY end of the process — and a gradle this run starts does not inherit the descriptor.
    Why it exists: a backup found at a start is put back; one whose maker is still running must not be."""
    fd = os.open(os.path.join(root, SRC), os.O_RDONLY)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        return None
    except BaseException:
        os.close(fd)
        raise
    return fd


def put_back(path):
    """The source at `path` gets its original back from its backup: ONE rename, so there is no moment with a
    cut or empty source. The backup is the original file itself under a second name; when the mutant never
    took the source's name the two are still one file, a rename of one over the other does nothing and leaves
    both, and so only the extra name is removed (False is returned then)."""
    backup = path + BACKUP
    if os.path.lexists(path) and os.path.samefile(backup, path):
        os.remove(backup)
        return False
    os.replace(backup, path)
    return True


def keep(path):
    """A second name beside `path` for what it holds now; returns that name. `os.link` refuses a name that is
    taken, so a file kept before is never replaced (`-2`, `-3`, …); a start that ended between this and its
    put-back finds the name it made and uses it again."""
    n = 1
    while True:
        left = path + LEFT + ('' if n == 1 else '-%d' % n)
        try:
            os.link(path, left)
            return left
        except FileExistsError:
            if os.path.samefile(path, left):
                return left
            n += 1


def recover(root, say):
    """Puts back what a run that ended without its `finally` (SIGKILL, power) left under the source root, one
    line each through `say`. Called with the tree's lock held, so nothing found here belongs to a live run:
    at the start of `main`, and at the end of a list (a stop can land inside `run_one`'s own `finally`).
    A source that is put back may have been edited since that run ended — a stage that did not know its file
    held a mutant — so what it holds is never dropped: it keeps a name (`keep`) and the line says which."""
    top = os.path.join(root, SRC)
    for backup in sorted(glob.glob(os.path.join(top, '**', '*' + BACKUP), recursive=True)):
        path = backup[:-len(BACKUP)]
        rel = os.path.relpath(path, root)
        if not os.path.lexists(path):
            os.replace(backup, path)
            say('put back %s from its %s: the source file itself was gone' % (rel, BACKUP))
        elif os.path.samefile(backup, path):
            os.remove(backup)
            say('removed %s%s: a run ended before its mutant was in the file, and %s is as it was' % (rel, BACKUP, rel))
        elif filecmp.cmp(backup, path, shallow=False):
            # put right by hand since (a checkout, an editor's undo): a kept copy would be the original again
            # (R2 item 29 a). The original file takes its name back, so the source is the same file again.
            os.replace(backup, path)
            say('put back %s from its %s: a run ended with its mutant in the file, and the file already held the '
                'original\'s bytes (put right since); nothing is kept' % (rel, BACKUP))
        else:
            left = keep(path)
            os.replace(backup, path)
            say('put back %s from its %s: a run ended with its mutant in the file. What the file held is kept '
                'as %s (the mutant, with anything edited on top of it since); remove it once looked at'
                % (rel, BACKUP, os.path.basename(left)))
    for temp in sorted(glob.glob(os.path.join(top, '**', '*' + TEMP), recursive=True)):
        os.remove(temp)
        say('removed %s: a mutant that never took its source\'s name' % os.path.relpath(temp, root))


def clear_results(root):
    """Remove the result files an earlier run left under `root` (GG-7; the module docstring says why)."""
    for fn in glob.glob(os.path.join(root, RESULTS)):
        os.remove(fn)


def results(root):
    """({classname.name: failed}, {classname.name: the failure's first line}) from the result files under `root`.
    Keyed by class AND name: keyed by the name alone, two classes holding a test of one name shared an entry
    and the file read last decided for both."""
    out, reasons = {}, {}
    for fn in sorted(glob.glob(os.path.join(root, RESULTS))):
        s = open(fn).read()
        for tc in re.findall(r'<testcase\s.*?(?:/>|</testcase>)', s, re.S):
            # the opening tag comes first, so the first match of each is its attribute (`\s`: not the tail
            # of `classname=`)
            key = re.search(r'\sclassname="([^"]*)"', tc).group(1) + '.' + re.search(r'\sname="([^"]*)"', tc).group(1)
            out[key] = bool(re.search(r'<(?:failure|error)[\s>/]', tc))
            m = re.search(r'<(?:failure|error) message="([^"]*)"', tc, re.S)
            if m:
                reasons[key] = html.unescape(m.group(1)).replace('\n', ' ')
    return out, reasons


def short(key):
    """`Class.name` for the report: the package every core test is in is not repeated on each line."""
    return key[len(PKG):] if key.startswith(PKG) else key


def keys_of(expect, r):
    """The results an expected name speaks of: `expect` is a bare test name, `Class.name`, or the full key."""
    return [k for k in sorted(r) if k == expect or k.endswith('.' + expect)]


def verdict(expect, r, code):
    """One mutant's line. `r` is {classname.name: failed} of THIS run, `code` gradle's exit status.
    An expected name is one of three things and the line says which (RE-2): it ran and failed wherever it ran
    (caught); it ran and passed somewhere (NOT CAUGHT BY); it has no result in this run (NOT RUN: a test
    renamed since the list was written, a `--tests` pattern that no longer covers its class — nothing is known
    about the guard, so the entry is not judged; the two used to print the same line). An expect that opens
    with `<` or `(` is no test name but the list's own sentence that no test pins the guard: NO PIN
    (declared), which is a judgment. A line that holds a NOT RUN starts with it."""
    failed = [k for k in sorted(r) if r[k]]
    ran = ' | ran %d' % len(r)
    if not r:
        return 'ERROR (gradle exit %d, no results)' % code + ran
    if code != 0 and not failed:
        return 'ERROR (gradle exit %d, no testcase failed)' % code + ran
    declared = [e for e in expect if e[:1] in ('<', '(')]
    named = [e for e in expect if e not in declared]
    not_run = [e for e in named if not keys_of(e, r)]
    # caught: the name ran, and failed wherever it ran (two classes may hold it; the list did not say which)
    passed = [e for e in named if keys_of(e, r) and not all(r[k] for k in keys_of(e, r))]
    said = [head + ', '.join(names) for head, names in
            (('NOT RUN: ', not_run), ('NOT CAUGHT BY: ', passed), ('NO PIN (declared): ', declared)) if names]
    if named and not not_run and not passed:
        said.insert(0, 'CAUGHT')
    return ('; '.join(said) or 'CAUGHT') + ' | failed: ' + ', '.join(short(k) for k in failed) + ran


def gradle_said(txt):
    """Why gradle ended, in one line, for an ERROR: its "What went wrong" block, or its last lines."""
    m = re.search(r'\* What went wrong:\n(.*?)(?:\n\s*\n|\n\* Try:|\Z)', txt, re.S)
    lines = [l.strip() for l in (m.group(1) if m else txt).splitlines() if l.strip()]
    return ' '.join(lines if m else lines[-3:])[:300] or '(gradle printed nothing)'


def run_one(root, m, check_only=False, why=False):
    """The report lines of one list entry on the tree at `root`: apply its edits, run its tests through
    `root`'s `./gradlew`, judge, put the file back (the module docstring says how, and what an end by a signal
    leaves). Every write is inside the `try`, and its `finally` puts back only a backup this call made."""
    path = os.path.join(root, SRC, m['file'])
    orig = open(path).read()
    s = orig
    for old, new in m['edits']:
        if s.count(old) != 1:
            return ['EDIT DID NOT APPLY (%d matches): %s' % (s.count(old), old[:70])]
        s = s.replace(old, new)
    if check_only:
        return ['applies']
    if not m['expect']:
        # with nothing expected, nothing is missing: every run would read CAUGHT
        return ['ERROR (the entry names no expected test)']
    backup, temp = path + BACKUP, path + TEMP
    theirs = os.path.lexists(backup)        # not this call's: `os.link` below refuses it, the `finally` leaves it
    try:
        os.link(path, backup)               # the original, under a name of its own BEFORE a mutant exists
        with open(temp, 'w') as f:
            f.write(s)
        os.replace(temp, path)              # one rename: the source is the original or the mutant, never part of one
        clear_results(root)
        args = ['./gradlew', ':core:cleanTest', ':core:test', '--no-build-cache', '--console=plain']
        for t in m['tests']:
            args += ['--tests', t]
        p = subprocess.run(args, cwd=root, capture_output=True, text=True)
        txt = p.stdout + p.stderr
        if 'Compilation error' in txt or re.search(r'^e: ', txt, re.M):
            return ['DID NOT COMPILE: ' + '; '.join(re.findall(r'^e: .*', txt, re.M))[:300]]
        r, reasons = results(root)
        lines = [verdict(m['expect'], r, p.returncode)]
        if lines[0].startswith('ERROR'):
            lines.append('    gradle: ' + gradle_said(txt))
        if why:
            for e in m['expect']:
                for k in keys_of(e, r):
                    if k in reasons:
                        lines.append('    why %s: %s' % (short(k), reasons[k][:400]))
        return lines
    finally:
        try:
            if not theirs and os.path.lexists(backup):
                put_back(path)
        finally:
            if os.path.lexists(temp):
                os.remove(temp)


def selftest():
    """The runner's own checks, on a scratch copy of the layout with a stand-in for `./gradlew` that writes
    what one test run would (result files, a compile error, an exit status). Nothing under the repository is
    read or written by a case and no gradle runs. Each case names what the runner has to say. The cases that
    need a runner of its own start one as a child in its own session and end that process group, and nothing
    else, in a `finally`; their waits have a bound that only fails the case."""
    import tempfile, shutil, io, contextlib, signal, time
    root = tempfile.mkdtemp(prefix='mutate-core-selftest-')
    A, B = PKG + 'ATest', PKG + 'BTest'

    def xml(cls, cases):
        body = ''
        for name, msg in cases:
            if msg is None:
                body += '  <testcase name="%s" classname="%s" time="0.01"/>\n' % (name, cls)
            else:
                body += ('  <testcase name="%s" classname="%s" time="0.01">\n    <failure message="%s" type='
                         '"java.lang.AssertionError">java.lang.AssertionError: %s\n\tat %s.%s(X.kt:1)\n</failure>\n'
                         '  </testcase>\n') % (name, cls, html.escape(msg), html.escape(msg), cls, name)
        return ('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="%s" tests="%d" skipped="0">\n'
                '  <properties/>\n%s  <system-out><![CDATA[]]></system-out>\n</testsuite>\n') % (cls, len(cases), body)

    src = os.path.join(root, SRC, 'x', 'Guard.kt')
    res = os.path.join(root, os.path.dirname(RESULTS))
    stub_out = os.path.join(root, 'stub-out')
    text = 'fun guard(x: Int) = if (x > 0) x else 0   // GUARD\n'
    edit = [['// GUARD', '// MUTATED']]
    stub = ('#!/bin/sh\n'
            '# the stand-in for ./gradlew in mutate_core.py --selftest: it does what the case files say\n'
            'echo "$@" >> stub-calls\n'
            'ls core/build/test-results/test | wc -l >> stub-stale      # result files still there at its start\n'
            'grep -c MUTATED core/src/main/kotlin/wm/damage/core/x/Guard.kt >> stub-mutated\n'
            '# a case that needs a gradle still running: one process, which ends by itself after ten minutes\n'
            'n="$(cat stub-block)"       # the call from which it stays (empty: never)\n'
            'if [ -n "$n" ] && [ "$(wc -l < stub-calls)" -ge "$n" ]; then echo blocked >> stub-blocked; exec sleep 600; fi\n'
            'cat stub-stdout\n'
            'cp stub-out/*.xml core/build/test-results/test/ 2>/dev/null\n'
            'exit "$(cat stub-exit)"\n')
    gone = 'guard gone'
    declared = '<no pin: named in the build report>'
    stale_fail = {'TEST-x.xml': xml(A, [('pin', 'a failure of the mutant before')])}
    both = {'TEST-A.xml': xml(A, [('pin', None)]), 'TEST-B.xml': xml(B, [('pin', gone)])}
    # (what the case shows, files left by the run before, files this run writes, gradle's exit, its output,
    #  the entry's edits, its expect, the lines the runner must say (the last may be a prefix, marked …))
    cases = [
        ("GG-7: a stale failed result and a gradle that exits 1 writing nothing is an ERROR, not a CAUGHT",
         stale_fail, {}, 1, 'FAILURE: Build failed with an exception.\n\n* What went wrong:\nTimeout waiting to lock build\n\n* Try:\n',
         edit, ['pin'], ['ERROR (gradle exit 1, no results) | ran 0', '    gradle: Timeout waiting to lock build']),
        ("a gradle that exits 0 writing nothing is an ERROR",
         {}, {}, 0, 'BUILD SUCCESSFUL in 1s\n', edit, ['pin'],
         ['ERROR (gradle exit 0, no results) | ran 0', '    gradle: BUILD SUCCESSFUL in 1s']),
        ("the expected test failing in this run is CAUGHT; the run before is not counted",
         {'TEST-x.xml': xml(B, [('stale', 'left by the run before')])},
         {'TEST-A.xml': xml(A, [('pin', gone), ('other', None)])}, 1, '', edit, ['pin'],
         ['CAUGHT | failed: ATest.pin | ran 2', '    why ATest.pin: ' + gone]),
        ("the expected test passing in this run is NOT CAUGHT, whatever the run before left",
         stale_fail, {'TEST-A.xml': xml(A, [('pin', None), ('other', None)])}, 0, '', edit, ['pin'],
         ['NOT CAUGHT BY: pin | failed:  | ran 2']),
        ("gradle exiting 1 with no failed testcase is an ERROR",
         {}, {'TEST-A.xml': xml(A, [('pin', None)])}, 1, 'Process \'Gradle Test Executor 1\' finished with non-zero exit value 137\n',
         edit, ['pin'], ['ERROR (gradle exit 1, no testcase failed) | ran 1', '    gradle: …']),
        ("another test failing is not the expected one failing",
         {}, {'TEST-A.xml': xml(A, [('pin', None), ('other', gone)])}, 1, '', edit, ['pin'],
         ['NOT CAUGHT BY: pin | failed: ATest.other | ran 2']),
        ("one name in two classes, failed in one: the bare name is not caught",
         {}, both, 1, '', edit, ['pin'], ['NOT CAUGHT BY: pin | failed: BTest.pin | ran 2', '    why BTest.pin: ' + gone]),
        ("...the class that failed, named, is caught",
         {}, both, 1, '', edit, ['BTest.pin'], ['CAUGHT | failed: BTest.pin | ran 2', '    why BTest.pin: ' + gone]),
        ("...and the class that passed, named, is not",
         {}, both, 1, '', edit, ['ATest.pin'], ['NOT CAUGHT BY: ATest.pin | failed: BTest.pin | ran 2']),
        ("a mutant that does not compile says so",
         stale_fail, {}, 1, 'e: file:///x/Guard.kt:1:1 Unresolved reference: nope\n', edit, ['pin'],
         ['DID NOT COMPILE: e: file:///x/Guard.kt:1:1 Unresolved reference: nope']),
        ("an edit that does not apply runs nothing",
         {}, {'TEST-A.xml': xml(A, [('pin', gone)])}, 1, '', [['not in the file', 'x']], ['pin'],
         ['EDIT DID NOT APPLY (0 matches): not in the file']),
        ("an entry that expects nothing is an ERROR, not a CAUGHT, and runs nothing",
         {}, {'TEST-A.xml': xml(A, [('pin', gone)])}, 1, '', edit, [],
         ['ERROR (the entry names no expected test)']),
        ("RE-2: an expected test that did not run is NOT RUN — it was read as one that ran and passed",
         {}, {'TEST-A.xml': xml(A, [('other', gone)])}, 1, '', edit, ['pin'],
         ['NOT RUN: pin | failed: ATest.other | ran 1']),
        ("RE-2: an expect that is a sentence is the list's own statement that no test pins the guard",
         {}, {'TEST-A.xml': xml(A, [('other', None)])}, 0, '', edit, [declared],
         ['NO PIN (declared): %s | failed:  | ran 1' % declared]),
        ("...one test that ran and failed, one that ran and passed, one that did not run: each is said",
         {}, {'TEST-A.xml': xml(A, [('pin', gone), ('other', None)])}, 1, '', edit, ['pin', 'other', 'absent'],
         ['NOT RUN: absent; NOT CAUGHT BY: other | failed: ATest.pin | ran 2', '    why ATest.pin: ' + gone]),
    ]
    lst, lst2, lst3 = (os.path.join(root, n) for n in ('list.json', 'list2.json', 'list3.json'))
    passing = {'TEST-A.xml': xml(A, [('pin', None)])}
    # the command line: (what the case shows, the arguments, files this run writes, gradle's exit, the exit
    #  status main must return, a line it must print, whether gradle may run)
    cli_cases = [
        ("an unknown flag is refused with status 2 and runs nothing (it was read as an id that matched nothing)",
         [lst, '--wy'], {}, 1, 2, 'unknown argument: --wy (flags: --check --why --selftest)', False),
        ("an id the list does not hold is refused with status 2 and runs nothing",
         [lst, 'cse'], {}, 1, 2, lst + ' holds no entry named cse', False),
        ("--check says the edits apply and runs nothing",
         [lst, '--check'], {}, 1, 0, 'case :: applies', False),
        ("an entry that could not be judged makes the exit status 1",
         [lst, 'case'], {}, 1, 1, 'case :: ERROR (gradle exit 1, no results) | ran 0', True),
        ("a list that was judged exits 0, caught or not",
         [lst], passing, 0, 0, 'case :: NOT CAUGHT BY: pin | failed:  | ran 1', True),
        ("RE-2: an expected test that did not run makes the exit status 1 (not judged)",
         [lst], {'TEST-A.xml': xml(A, [('other', None)])}, 0, 1, 'case :: NOT RUN: pin | failed:  | ran 1', True),
        ("RE-2: a declared gap is judged: exit 0",
         [lst3], passing, 0, 0, 'case :: NO PIN (declared): (no pin: the build report says why) | failed:  | ran 1', True),
    ]

    staged = {}

    def stage(stale, out, code, said, block=''):
        for f in os.listdir(os.path.dirname(src)):
            os.remove(os.path.join(os.path.dirname(src), f))
        open(src, 'w').write(text)
        staged['file'] = (os.stat(src).st_ino, os.stat(src).st_mtime_ns)
        for d in (res, stub_out):
            shutil.rmtree(d, ignore_errors=True)
            os.makedirs(d)
        for name, body in stale.items():
            open(os.path.join(res, name), 'w').write(body)
        for name, body in out.items():
            open(os.path.join(stub_out, name), 'w').write(body)
        open(os.path.join(root, 'stub-exit'), 'w').write(str(code))
        open(os.path.join(root, 'stub-stdout'), 'w').write(said)
        for f in ('stub-calls', 'stub-stale', 'stub-mutated', 'stub-blocked'):
            open(os.path.join(root, f), 'w').close()
        open(os.path.join(root, 'stub-block'), 'w').write(str(block))

    def left(kept=()):
        """What every case leaves: the source as it was — the same FILE, its time and inode too (a put-back
        that wrote a new file would have to answer what a build tool that knows a file by its size and time
        makes of it) — and no other file beside it but `kept`."""
        notes = [] if open(src).read() == text else ['the source file was not put back']
        if not notes and (os.stat(src).st_ino, os.stat(src).st_mtime_ns) != staged['file']:
            notes.append('the source holds its text but is not the file it was (its inode or its time changed)')
        extra = sorted(set(os.listdir(os.path.dirname(src))) - {'Guard.kt'} - set(kept))
        return notes + (['left beside the source: ' + ', '.join(extra)] if extra else [])

    def after(runs):
        """What every case leaves, whatever it said: the source as it was; gradle run or not, as the case
        says; and when it ran, the edit in the file and no result file of the run before."""
        notes = left()
        ran_gradle = open(os.path.join(root, 'stub-calls')).read().strip()
        if runs != bool(ran_gradle):
            notes.append('gradle %s' % ('did not run' if runs else 'ran: ' + ran_gradle))
        if runs and ran_gradle:
            if ':core:cleanTest :core:test --no-build-cache --console=plain --tests *ATest' not in ran_gradle:
                notes.append('gradle was asked %r' % ran_gradle)
            if open(os.path.join(root, 'stub-mutated')).read().strip() != '1':
                notes.append('the edit was not in the file while gradle ran')
            if open(os.path.join(root, 'stub-stale')).read().strip() != '0':
                notes.append('the result files of the run before were still there when gradle started')
        return notes

    def runner(argv, hup_ignored=False, again_at_put_back=False, at_the_end=None, at_the_start=False):
        """A runner of its own on the scratch root: a child in its own session, so its process group is the
        child and the stand-in gradle it starts and nothing else. Its output goes to a file. The signals
        start as this case says, whatever the self-test itself was started under (`nohup` ignores SIGHUP; a
        shell's background job can start with SIGINT ignored). `again_at_put_back`: the child sends itself
        one more SIGTERM as the first statement of its put-back — the worst moment for a second one.
        `at_the_end`: the child sends itself a SIGTERM in the END of its list — 'put-back': as the first
        statement of the put-back there (the second call of `recover`; the first is the start of `main`);
        'report': as it prints the first line of its report."""
        code = ('import os, signal, sys\nsys.path.insert(0, %r)\nimport %s as runner\n'
                'signal.signal(signal.SIGTERM, signal.SIG_DFL)\nsignal.signal(signal.SIGHUP, signal.%s)\n'
                'signal.signal(signal.SIGINT, signal.default_int_handler)\n'
                % (os.path.dirname(os.path.abspath(__file__)), os.path.splitext(os.path.basename(__file__))[0],
                   'SIG_IGN' if hup_ignored else 'SIG_DFL'))
        if again_at_put_back:
            code += ('real = runner.put_back\n'
                     'def put_back(path):\n'
                     '    os.kill(os.getpid(), signal.SIGTERM)\n'
                     '    return real(path)\n'
                     'runner.put_back = put_back\n')
        if at_the_end == 'put-back':
            code += ('real, calls = runner.recover, []\n'
                     'def recover(root, say):\n'
                     '    calls.append(1)\n'
                     '    if len(calls) == 2:\n'
                     '        os.kill(os.getpid(), signal.SIGTERM)\n'
                     '    return real(root, say)\n'
                     'runner.recover = recover\n')
        if at_the_end == 'report':
            code += ('import builtins\nsent = []\n'
                     'def say(*a, **k):\n'
                     '    if not sent and a[1:2] == ("::",):\n'
                     '        sent.append(1)\n'
                     '        os.kill(os.getpid(), signal.SIGTERM)\n'
                     '    builtins.print(*a, **k)\n'
                     'runner.print = say\n')
        if at_the_start:
            # R2 item 29 (a): a stop that lands after the handlers are set and before the first entry is
            # reached — the child sends itself SIGTERM as the list's loop begins (the first iteration with
            # the runner's own handler in place; the list is iterated once before, for its ids)
            code += ('import json as real_json\n'
                     'class Stopping(list):\n'
                     '    def __iter__(self):\n'
                     '        if signal.getsignal(signal.SIGTERM) not in (signal.SIG_DFL, signal.SIG_IGN):\n'
                     '            os.kill(os.getpid(), signal.SIGTERM)\n'
                     '        return list.__iter__(self)\n'
                     'class J:\n'
                     '    @staticmethod\n'
                     '    def load(f): return Stopping(real_json.load(f))\n'
                     'runner.json = J\n')
        code += 'sys.exit(runner.main(%r, %r))\n' % (argv, root)
        with open(os.path.join(root, 'runner-out'), 'w') as out:
            return subprocess.Popen([sys.executable, '-B', '-c', code], stdout=out, stderr=subprocess.STDOUT,
                                    start_new_session=True)

    def until(what, seconds=60):
        """Waits for `what()`. The bound only FAILS a case; nothing else is decided by it."""
        end = time.monotonic() + seconds
        while True:
            if what():
                return True
            if time.monotonic() >= end:
                return False
            time.sleep(0.01)

    def blocked():
        return os.path.getsize(os.path.join(root, 'stub-blocked')) > 0

    def ended(p):
        """The child's end, read WITHOUT reaping it: its pid — its group's id — stays taken until `finish`."""
        return os.waitid(os.P_PID, p.pid, os.WEXITED | os.WNOWAIT | os.WNOHANG)

    def how(info):
        return ('exit status %d' if info.si_code == os.CLD_EXITED else 'signal %d') % info.si_status

    def finish(p):
        """Ends the child's own process group (the child and the stand-in it started; nothing by name), then
        reaps the child. The group id cannot have gone to another process: the child was not reaped before."""
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        p.wait()

    def stopped_by(sig, want, hup_ignored=False, then=None, again_at_put_back=False, says=True):
        """A runner whose gradle is running gets `sig` (and `then` behind it); it must end by `want` with the
        source put back, nothing left beside it, and (`says`) a line that names the signal and the entry."""
        stage({}, {}, 0, '', block=1)
        p = runner([lst, 'case'], hup_ignored, again_at_put_back)
        notes = []
        try:
            if not until(blocked):
                return ['the stand-in gradle had not started after 60 s']
            if 'MUTATED' not in open(src).read():
                notes.append('the edit was not in the file while gradle ran')
            os.kill(p.pid, sig)
            if then is not None:
                os.kill(p.pid, then)
            if not until(lambda: ended(p)):
                notes.append('the runner had not ended 60 s after the signal')
            elif how(ended(p)) != want:
                notes.append('the runner ended by %s, not by %s' % (how(ended(p)), want))
        finally:
            finish(p)
        name = signal.Signals(sig if then is None else then).name
        if says and not any(l.startswith('ended by %s during case' % name) for l in open(os.path.join(root, 'runner-out'))):
            notes.append('the runner did not say that %s ended it during the entry' % name)
        return notes + left()

    def ended_by_sigkill_then_started():
        """SIGKILL leaves the mutant in the file (nothing can run). The next start of the tool on the tree —
        here a `--check` — puts the source back first and says so; what the file held keeps a name. The
        stand-in gradle is still running at that start: a lock it had inherited would refuse it."""
        stage({}, {}, 0, '', block=1)
        p = runner([lst, 'case'])
        notes = []
        try:
            if not until(blocked):
                return ['the stand-in gradle had not started after 60 s']
            os.kill(p.pid, signal.SIGKILL)
            if not until(lambda: ended(p)):
                return ['the runner had not ended 60 s after SIGKILL']
            mutant = open(src).read()
            if 'MUTATED' not in mutant:
                notes.append('SIGKILL did not leave the mutant in the file: the case shows nothing')
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed), contextlib.redirect_stderr(printed):
                got = main([lst, '--check'], root)
            lines = printed.getvalue().splitlines()
            if got != 0:
                notes.append('exit status %r, not 0' % got)
            if 'case :: applies' not in lines:
                notes.append('printed %r, without the line %r' % (printed.getvalue(), 'case :: applies'))
            if not any(l.startswith('put back %sx/Guard.kt' % SRC) for l in lines):
                notes.append('no line said that the source was put back')
            kept = src + '.mutate-left'
            if not os.path.isfile(kept) or open(kept).read() != mutant:
                notes.append('what the file held when it was put back was not kept as Guard.kt.mutate-left')
        finally:
            finish(p)
        return notes + left(kept=['Guard.kt.mutate-left'])

    def stopped_at_the_put_back():
        """Gradle returns; the FIRST stop lands at the first statement of the put-back (the child sends it to
        itself there), so `run_one`'s own `finally` is left before it put anything back. The end of the list
        must finish it: the source back, what it held kept, and the last line true."""
        stage({}, passing, 0, '')
        p = runner([lst, 'case'], again_at_put_back=True)
        notes = []
        try:
            if not until(lambda: ended(p)):
                notes.append('the runner had not ended after 60 s')
            elif how(ended(p)) != 'exit status 143':
                notes.append('the runner ended by %s, not by exit status 143' % how(ended(p)))
        finally:
            finish(p)
        out = open(os.path.join(root, 'runner-out')).read().splitlines()
        if not any(l.startswith('put back %sx/Guard.kt' % SRC) for l in out):
            notes.append('no line said that the source was put back: %r' % out)
        if 'ended by SIGTERM during case: that entry was not judged; no source file is left changed' not in out:
            notes.append('the last line is missing: %r' % out)
        kept = src + '.mutate-left'
        if not os.path.isfile(kept) or 'MUTATED' not in open(kept).read():
            notes.append('what the file held was not kept as Guard.kt.mutate-left')
        return notes + left(kept=['Guard.kt.mutate-left'])

    def judged_before_the_signal():
        """Two entries; gradle returns for the first and stays for the second, then SIGTERM. The first one's
        verdict must still be printed (the report is printed at the end, and that end is now reached)."""
        stage({}, passing, 0, '', block=2)
        p = runner([lst2])
        notes = []
        try:
            if not until(blocked):
                return ['the stand-in gradle had not stayed at its second call after 60 s']
            os.kill(p.pid, signal.SIGTERM)
            if not until(lambda: ended(p)):
                notes.append('the runner had not ended 60 s after the signal')
        finally:
            finish(p)
        out = open(os.path.join(root, 'runner-out')).read().splitlines()
        for line in ('first :: NOT CAUGHT BY: pin | failed:  | ran 1',
                     'ended by SIGTERM during case: that entry was not judged; no source file is left changed'):
            if line not in out:
                notes.append('printed %r, without the line %r' % (out, line))
        return notes + left()

    def stopped_in_the_end(where):
        """The list ended by itself, every entry judged, and a stop lands in its END (`where`: at the put-back
        there, or at the first line of the report). There is no gradle left to end and nothing to stop: the
        report — hours of work for a whole list — must be printed whole, the exit status is the list's own,
        and a line says that the signal came."""
        stage({}, passing, 0, '')
        p = runner([lst2], at_the_end=where)
        notes = []
        try:
            if not until(lambda: ended(p)):
                notes.append('the runner had not ended after 60 s')
            elif how(ended(p)) != 'exit status 0':
                notes.append('the runner ended by %s, not by exit status 0' % how(ended(p)))
        finally:
            finish(p)
        out = open(os.path.join(root, 'runner-out')).read().splitlines()
        for line in ('first :: NOT CAUGHT BY: pin | failed:  | ran 1', 'case :: NOT CAUGHT BY: pin | failed:  | ran 1'):
            if line not in out:
                notes.append('printed %r, without the line %r' % (out, line))
        if not any(l.startswith('SIGTERM arrived as the list ended') for l in out):
            notes.append('no line said that SIGTERM arrived as the list ended: %r' % out)
        return notes + left()

    def handlers_as_before():
        """Once a list has ended the two signals are handled as they were before it: nothing of this tool
        stays installed in a process that goes on (the self-test itself is one)."""
        mine = lambda signum, frame: None
        was = {s: signal.signal(s, mine) for s in SIGNALS}
        try:
            stage({}, {}, 0, '')
            got, lines = started()
            notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
            notes += ['%s is not handled as it was before the list' % signal.Signals(s).name
                      for s in SIGNALS if signal.getsignal(s) is not mine]
        finally:
            for s, h in was.items():
                signal.signal(s, signal.SIG_DFL if h is None else h)
        return notes + left()

    def held_by_a_live_run():
        """A second start while a run is live: the backup it finds belongs to that run. It must put nothing
        back, check nothing, say why and return 2; the live run then ends as if nothing had happened."""
        stage({}, {}, 0, '', block=1)
        p = runner([lst, 'case'])
        notes = []
        try:
            if not until(blocked):
                return ['the stand-in gradle had not started after 60 s']
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed), contextlib.redirect_stderr(printed):
                got = main([lst, '--check'], root)
            lines = printed.getvalue().splitlines()
            if got != 2:
                notes.append('exit status %r, not 2' % got)
            if not any(l.startswith('another run of this tool holds ') for l in lines):
                notes.append('no line said that another run holds the tree: %r' % printed.getvalue())
            if any(l.startswith('case ::') for l in lines):
                notes.append('an entry was checked against the live run\'s mutant: %r' % printed.getvalue())
            if 'MUTATED' not in open(src).read():
                notes.append('the live run\'s mutant was put back under it')
            if not os.path.isfile(src + '.mutate-orig'):
                notes.append('the live run\'s backup was taken')
            os.kill(p.pid, signal.SIGTERM)
            if not until(lambda: ended(p)):
                notes.append('the live run had not ended 60 s after SIGTERM')
            elif how(ended(p)) != 'exit status 143':
                notes.append('the live run ended by %s, not by exit status 143' % how(ended(p)))
        finally:
            finish(p)
        return notes + left()

    def started(argv=None, root=root):
        """One start of the tool on the scratch root (a `--check`): (exit status, the lines it printed)."""
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed), contextlib.redirect_stderr(printed):
            got = main(argv or [lst, '--check'], root)
        return got, printed.getvalue().splitlines()

    def a_temp_file_left():
        stage({}, {}, 0, '')
        open(src + '.mutate-tmp', 'w').write('a mutant that never took the name\n')
        got, lines = started()
        notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
        if not any(l.startswith('removed %sx/Guard.kt.mutate-tmp' % SRC) for l in lines):
            notes.append('no line said that the temp file was removed: %r' % lines)
        return notes + left()

    def a_backup_that_is_the_source():
        """A run that ended between naming its backup and placing its mutant: the two names are one file."""
        stage({}, {}, 0, '')
        os.link(src, src + '.mutate-orig')
        got, lines = started()
        notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
        if not any(l.startswith('removed %sx/Guard.kt.mutate-orig' % SRC) for l in lines):
            notes.append('no line said that the extra name was removed: %r' % lines)
        return notes + left()

    def a_kept_file_is_not_replaced():
        """Two recoveries of one file: what the second one keeps must not take the name of what the first kept."""
        stage({}, {}, 0, '')
        os.link(src, src + '.mutate-orig')
        os.remove(src)
        open(src, 'w').write('the mutant, with an edit made on top of it\n')
        open(src + '.mutate-left', 'w').write('kept by a start before this one\n')
        got, lines = started()
        notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
        if open(src + '.mutate-left').read() != 'kept by a start before this one\n':
            notes.append('the file kept before was replaced')
        second = src + '.mutate-left-2'
        if not os.path.isfile(second) or open(second).read() != 'the mutant, with an edit made on top of it\n':
            notes.append('what the source held was not kept as Guard.kt.mutate-left-2')
        if not any(l.startswith('put back %sx/Guard.kt' % SRC) and 'Guard.kt.mutate-left-2' in l for l in lines):
            notes.append('no line named the kept file: %r' % lines)
        return notes + left(kept=['Guard.kt.mutate-left', 'Guard.kt.mutate-left-2'])

    def a_start_ended_at_its_own_put_back():
        """A start that kept what the source held and was itself ended before its put-back: the name it made
        is a second name of the source as it is. The next start uses that name — the same file is not kept twice."""
        stage({}, {}, 0, '')
        os.link(src, src + '.mutate-orig')
        os.remove(src)
        open(src, 'w').write('the mutant\n')
        os.link(src, src + '.mutate-left')
        got, lines = started()
        notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
        if not any(l.startswith('put back %sx/Guard.kt' % SRC) and 'kept as Guard.kt.mutate-left (' in l for l in lines):
            notes.append('no line named Guard.kt.mutate-left as the kept file: %r' % lines)
        if not os.path.isfile(src + '.mutate-left') or open(src + '.mutate-left').read() != 'the mutant\n':
            notes.append('what the source held is not in Guard.kt.mutate-left')
        return notes + left(kept=['Guard.kt.mutate-left'])

    def a_source_put_right_by_hand():
        """R2 item 29 (a): a run that ended with its mutant in the file, and the file since put right by hand
        (a new file with the original's bytes): the start puts the original back and keeps nothing — a kept
        copy would only be the original again."""
        stage({}, {}, 0, '')
        os.link(src, src + '.mutate-orig')
        os.remove(src)
        open(src, 'w').write(text)
        got, lines = started()
        notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
        if not any(l.startswith('put back %sx/Guard.kt' % SRC) and 'already held the original' in l for l in lines):
            notes.append('no line said that the file already held the original\'s bytes: %r' % lines)
        return notes + left()

    def stopped_before_the_first_entry():
        """R2 item 29 (a): a stop that lands before the first entry names no entry ("during None")."""
        stage({}, {}, 0, '')
        p = runner([lst, 'case'], at_the_start=True)
        notes = []
        try:
            if not until(lambda: ended(p)):
                return ['the runner had not ended 60 s after its own SIGTERM']
            if how(ended(p)) != 'exit status 143':
                notes.append('the runner ended by %s, not by exit status 143' % how(ended(p)))
        finally:
            finish(p)
        out = open(os.path.join(root, 'runner-out')).read()
        if 'ended by SIGTERM before the first entry: nothing was judged; no source file is left changed' not in out:
            notes.append('the runner said %r' % out.strip())
        return notes + after(False)

    def a_selftest_beside_a_live_run():
        """R2 item 29 (a): `--selftest` while another run holds the tree: it runs on a scratch root of its own,
        and the line says that — not "nothing is put back, run or checked". (The self-test itself is a stand-in
        here: the case reads the line, it does not run the cases twice.)"""
        stage({}, {}, 0, '')
        fd = claim(root)                                # the tree held, as a live run holds it
        real = globals()['selftest']
        globals()['selftest'] = lambda: 0
        try:
            got, lines = started(['--selftest'])
        finally:
            globals()['selftest'] = real
            os.close(fd)
        notes = [] if got == 0 else ['exit status %r, not 0' % got]
        want = 'another run of this tool holds %s (the lock on %s): nothing there is put back or read; ' \
               'the self-test runs on a scratch root of its own' % (root, SRC)
        if want not in lines:
            notes.append('printed %r' % lines)
        return notes + left()

    def a_source_that_is_gone():
        """A backup whose source has no file at all: the start gives the original its name back and says so."""
        stage({}, {}, 0, '')
        os.link(src, src + '.mutate-orig')
        os.remove(src)
        try:
            got, lines = started()
        except OSError as e:
            return ['the start ended as a fault (%s): the source has no file' % type(e).__name__]
        notes = [] if got == 0 and 'case :: applies' in lines else ['exit status %r, printed %r' % (got, lines)]
        if not any(l.startswith('put back %sx/Guard.kt' % SRC) and 'itself was gone' in l for l in lines):
            notes.append('no line said that the source was gone and is back: %r' % lines)
        return notes + left()

    def not_a_tree():
        """A root with no source directory is refused with a line (it ended as a fault on the first entry's file)."""
        other = tempfile.mkdtemp(prefix='mutate-core-selftest-other-')
        try:
            got, lines = started(root=other)
        finally:
            shutil.rmtree(other, ignore_errors=True)
        return [n for n in ['exit status %r, not 2' % got if got != 2 else '',
                            'printed %r, with no line that says why' % lines if not any('not a tree this tool runs on' in l for l in lines) else ''] if n]

    entry = {'id': 'case', 'file': 'x/Guard.kt', 'edits': edit, 'tests': ['*ATest'], 'expect': ['pin']}

    def a_write_that_fails():
        """The mutant's rename fails. The backup already has its name by then: the `finally` must take it away
        again (every write is inside the `try`), and the failure must leave `run_one` as it is."""
        stage({}, {}, 0, '')
        real, seen = os.replace, []

        def failing(a, b):
            seen.append(b)
            if b == src and seen.count(src) == 1:
                raise OSError('the stand-in for a rename that fails')
            return real(a, b)
        os.replace = failing
        try:
            run_one(root, entry)
            notes = ['run_one returned although the mutant could not be placed']
        except OSError as e:
            notes = [] if 'the stand-in for a rename that fails' in str(e) else ['raised %r' % e]
        finally:
            os.replace = real
        return notes + after(False)

    def a_backup_of_another_run():
        """`run_one` called on a file whose backup is already there (not through `main`, which puts it back
        first): it must refuse, run nothing, and leave that backup and the source alone."""
        stage({}, {}, 0, '')
        open(src + '.mutate-orig', 'w').write('the original, as another run saved it\n')
        try:
            run_one(root, entry)
            notes = ['run_one ran with a backup of another run in place']
        except FileExistsError:
            notes = []
        theirs = src + '.mutate-orig'
        if not os.path.isfile(theirs) or open(theirs).read() != 'the original, as another run saved it\n':
            notes.append('the backup of the other run was taken or changed')
        if open(os.path.join(root, 'stub-calls')).read().strip():
            notes.append('gradle ran')
        return notes + left(kept=['Guard.kt.mutate-orig'])

    # (what the case shows, the case: it returns what was wrong)
    own_runner_cases = [
        ("RE-1: SIGTERM to a runner whose gradle is running: the source is put back and the runner says so",
         lambda: stopped_by(signal.SIGTERM, 'exit status 143')),
        ("RE-1: SIGKILL leaves the mutant; the next start of the tool puts the source back and says so",
         ended_by_sigkill_then_started),
        ("SIGHUP is handled as SIGTERM is (exit status 129)",
         lambda: stopped_by(signal.SIGHUP, 'exit status 129')),
        ("a SIGHUP that was ignored when the tool started (nohup) stays ignored: the SIGTERM behind it ends the run",
         lambda: stopped_by(signal.SIGHUP, 'exit status 143', hup_ignored=True, then=signal.SIGTERM)),
        ("a second SIGTERM, sent at the first statement of the put-back, does not stop it",
         lambda: stopped_by(signal.SIGTERM, 'exit status 143', again_at_put_back=True)),
        ("a stop that lands at the put-back itself: the end of the list finishes the put-back, and says so",
         stopped_at_the_put_back),
        ("Ctrl-C (SIGINT, Python's own KeyboardInterrupt) puts the source back as before",
         lambda: stopped_by(signal.SIGINT, 'signal 2', says=False)),
        ("what was judged before the signal is printed, and the entry that was not is named",
         judged_before_the_signal),
        ("a stop that lands in the END of a list that ended by itself, at its put-back: the report is printed whole",
         lambda: stopped_in_the_end('put-back')),
        ("...and at the first line of the report: the report is printed whole",
         lambda: stopped_in_the_end('report')),
        ("once a list has ended SIGTERM and SIGHUP are handled as they were before it", handlers_as_before),
        ("a second start while a run is live puts nothing back, checks nothing and returns 2",
         held_by_a_live_run),
        ("a start removes a temp file a run left and says so", a_temp_file_left),
        ("a backup that is still the source itself (no mutant was placed) only loses its name", a_backup_that_is_the_source),
        ("what a start keeps of a source it puts back never replaces a file kept before", a_kept_file_is_not_replaced),
        ("a start that was itself ended between keeping and putting back: the next one uses the name it made",
         a_start_ended_at_its_own_put_back),
        ("a backup whose source is gone gives the source its file back", a_source_that_is_gone),
        ("R2: a source put right by hand since its run ended is put back and nothing is kept beside it",
         a_source_put_right_by_hand),
        ("R2: a stop before the first entry says so (it named no entry: \"during None\")", stopped_before_the_first_entry),
        ("R2: --selftest beside a live run says it runs on a scratch root, not that nothing is run",
         a_selftest_beside_a_live_run),
        ("a root that holds no source directory is refused with a line and status 2", not_a_tree),
        ("a mutant that cannot be placed leaves the source, and nothing beside it", a_write_that_fails),
        ("a backup that was there before is not this run's: nothing runs and nothing is put back", a_backup_of_another_run),
    ]
    missed = 0

    def say(title, notes):
        nonlocal missed
        missed += bool(notes)
        print('%s  %s%s' % ('FAIL' if notes else 'ok  ', title, ''.join('\n        ' + n for n in notes)))

    try:
        os.makedirs(os.path.dirname(src))
        os.makedirs(res)
        open(os.path.join(root, 'gradlew'), 'w').write(stub)
        os.chmod(os.path.join(root, 'gradlew'), 0o755)
        json.dump([entry], open(lst, 'w'))
        json.dump([dict(entry, id='first'), entry], open(lst2, 'w'))
        json.dump([dict(entry, expect=['(no pin: the build report says why)'])], open(lst3, 'w'))
        for title, stale, out, code, said, edits, expect, want in cases:
            stage(stale, out, code, said)
            got = run_one(root, {'id': 'case', 'file': 'x/Guard.kt', 'edits': edits, 'tests': ['*ATest'], 'expect': expect},
                          why=True)
            notes = []
            if len(got) != len(want) or any(g != w and not (w.endswith('…') and g.startswith(w[:-1])) for g, w in zip(got, want)):
                notes.append('said %r, not %r' % (got, want))
            runs = not (want[0].startswith('EDIT DID NOT APPLY') or want[0] == 'ERROR (the entry names no expected test)')
            say(title, notes + after(runs))
        for title, argv, out, code, status, line, runs in cli_cases:
            stage(stale_fail, out, code, '')
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed), contextlib.redirect_stderr(printed):
                got = main(argv, root)
            notes = []
            if got != status:
                notes.append('exit status %r, not %d' % (got, status))
            if line not in printed.getvalue().splitlines():
                notes.append('printed %r, without the line %r' % (printed.getvalue(), line))
            say(title, notes + after(runs))
        for title, case in own_runner_cases:
            say(title, case())
    finally:
        shutil.rmtree(root, ignore_errors=True)
    n = len(cases) + len(cli_cases) + len(own_runner_cases)
    print('selftest: %d of %d' % (n - missed, n))
    print('not covered here: a real gradle run and its result files (run one list entry for that)')
    return 1 if missed else 0


def main(argv, root=ROOT):
    tree = os.path.isdir(os.path.join(root, SRC))
    lock = claim(root) if tree else None
    try:
        # first, whatever the arguments: what a run that could not put its source back has left (RE-1)
        if lock is not None:
            recover(root, lambda line: print(line, file=sys.stderr))
        elif tree and '--selftest' in argv:
            # the self-test runs on a scratch root of its own; only this tree is left alone (R2 item 29 a)
            print('another run of this tool holds %s (the lock on %s): nothing there is put back or read; '
                  'the self-test runs on a scratch root of its own' % (root, SRC), file=sys.stderr)
        elif tree:
            print('another run of this tool holds %s (the lock on %s): nothing is put back, run or checked '
                  'until it ends' % (root, SRC), file=sys.stderr)
        unknown = [a for a in argv if a.startswith('-') and a not in FLAGS]
        if unknown:
            # an unknown flag used to be read as an entry id, match nothing, print nothing and exit 0
            print('unknown argument: %s (flags: %s)' % (' '.join(unknown), ' '.join(FLAGS)), file=sys.stderr)
            return 2
        if '--selftest' not in argv:
            return run_list(argv, root, tree, lock is not None)
    finally:
        if lock is not None:
            os.close(lock)
    return selftest()                       # on a scratch root of its own; this tree's lock is released


def run_list(argv, root, tree, free):
    """The list named in `argv` on the tree at `root`. `tree` = the source directory is there; `free` = this
    process holds its lock."""
    rest = [a for a in argv if a not in FLAGS]
    if not rest:
        sys.exit(__doc__)
    mut = json.load(open(rest[0]))
    only = set(rest[1:])
    absent = sorted(only - {m['id'] for m in mut})
    if absent:
        print('%s holds no entry named %s' % (rest[0], ', '.join(absent)), file=sys.stderr)
        return 2
    if not tree:
        print('%s holds no %s: not a tree this tool runs on' % (root, SRC), file=sys.stderr)
        return 2
    if not free:
        return 2                            # said at the start of main; the other run's source is not read
    report, got, late, at, closing = [], [], [], [None], []

    def stop(signum, frame):
        """SIGTERM / SIGHUP: Python's default ends the process with no `finally` and the mutant stays in the
        source. Raised as SystemExit, `subprocess.run` ends the gradle client and `run_one` puts the file
        back. Both are ignored from here on: a second one must not interrupt that put-back.
        Once the list is over and its END is running (`closing`: the put-back there, the report) there is no
        gradle to end and nothing to stop; raised there it cost every line of the report. It is noted, and
        the end finishes by itself."""
        for s in SIGNALS:
            signal.signal(s, signal.SIG_IGN)
        if closing:
            late.append(signum)
            return
        got.append(signum)
        raise SystemExit(128 + signum)

    before = {}
    try:
        for s in SIGNALS:
            if signal.getsignal(s) != signal.SIG_IGN:          # ignored at the start (`nohup`): stays ignored
                before[s] = signal.signal(s, stop)
        for m in mut:
            if only and m['id'] not in only:
                continue
            at[0] = m['id']
            for line in run_one(root, m, check_only='--check' in argv, why='--why' in argv):
                report.append((m['id'], line))
    finally:
        closing.append(True)                # first: from here a stop is noted by `stop`, not raised
        try:
            # A stop is raised between two statements, and one of the places it can land is `run_one`'s own
            # `finally`, ahead of its put-back. This process holds the tree's lock, so a backup still there is
            # this run's: put it back before anything is said (after a stop both signals are still ignored here).
            recover(root, lambda line: print(line, file=sys.stderr))
            for r in report:
                print(r[0], '::', r[1])
            if got and at[0] is None:
                # landed before the first entry was reached (R2 item 29 a: it read "during None")
                print('ended by %s before the first entry: nothing was judged; no source file is left changed'
                      % signal.Signals(got[0]).name, file=sys.stderr)
            elif got:
                print('ended by %s during %s: that entry was not judged; no source file is left changed'
                      % (signal.Signals(got[0]).name, at[0]), file=sys.stderr)
            if late:
                print('%s arrived as the list ended: nothing was left to stop, and the report is printed whole'
                      % signal.Signals(late[0]).name, file=sys.stderr)
        finally:
            # LAST: put back ahead of the report, the handlers of before let a stop that arrived while it was
            # printed end the process by its default action, the report cut short.
            for s, was in before.items():
                signal.signal(s, signal.SIG_DFL if was is None else was)
    return 1 if any(r[1].startswith(NOT_JUDGED) for r in report) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
