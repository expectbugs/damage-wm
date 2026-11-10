#!/usr/bin/env python3
"""The mutation runner for Damage's core (HANDOFF.md §73): apply one source mutation at a time, run the test
classes the mutation names, report which tests fail, restore the file.

    python3 research/mutate_core.py research/mutations-73.json --check        # do the edits still apply? no build
    python3 research/mutate_core.py research/mutations-73.json                # the whole list (hours)
    python3 research/mutate_core.py research/mutations-73.json M1-no-promotion L1-not-isolated-counted

A list entry: {"id", "file" (under core/src/main/kotlin/wm/damage/core/), "edits": [[old, new], …] (each `old`
must occur exactly once), "tests": gradle --tests patterns, "expect": the test names that must fail}.
CAUGHT = every expected test failed under the mutation. It rewrites a source file while it runs and puts it back
in a `finally`: run nothing else on the tree meanwhile, and check `git status` if it is interrupted.
The fork's own sweep is ~/damage-cfw/tools/mutate.py; this one is the phone-side twin."""
import subprocess, sys, re, glob, os, json
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = ROOT + '/core/src/main/kotlin/wm/damage/core/'
argv = [a for a in sys.argv[1:] if a != '--check']
check_only = '--check' in sys.argv[1:]
if not argv:
    sys.exit(__doc__)
MUT = json.load(open(argv[0]))
only = set(argv[1:])


def results():
    out = {}
    for fn in glob.glob(ROOT + '/core/build/test-results/test/TEST-*.xml'):
        s = open(fn).read()
        for tc in re.findall(r'<testcase .*?(?:/>|</testcase>)', s, re.S):
            name = re.search(r'name="([^"]+)"', tc).group(1)
            out[name] = bool(re.search(r'<(?:failure|error) ', tc))
    return out


report = []
for m in MUT:
    if only and m['id'] not in only:
        continue
    path = SRC + m['file']
    orig = open(path).read()
    s = orig
    ok = True
    for old, new in m['edits']:
        if s.count(old) != 1:
            report.append((m['id'], 'EDIT DID NOT APPLY (%d matches): %s' % (s.count(old), old[:70])))
            ok = False
            break
        s = s.replace(old, new)
    if not ok:
        continue
    if check_only:
        report.append((m['id'], 'applies'))
        continue
    open(path, 'w').write(s)
    try:
        args = ['./gradlew', ':core:cleanTest', ':core:test', '--no-build-cache', '--console=plain']
        for t in m['tests']:
            args += ['--tests', t]
        p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
        if 'Compilation error' in p.stdout + p.stderr or re.search(r'^e: ', p.stdout + p.stderr, re.M):
            report.append((m['id'], 'DID NOT COMPILE: ' + '; '.join(re.findall(r'^e: .*', p.stdout + p.stderr, re.M))[:300]))
            continue
        r = results()
        failed = sorted(k for k, v in r.items() if v)
        missing = sorted(set(m['expect']) - set(failed))
        report.append((m['id'], ('CAUGHT' if not missing else 'NOT CAUGHT BY: ' + ', '.join(missing))
                       + ' | failed: ' + ', '.join(failed) + ' | ran %d' % len(r)))
    finally:
        open(path, 'w').write(orig)
for r in report:
    print(r[0], '::', r[1])
