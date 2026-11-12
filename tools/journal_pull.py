#!/usr/bin/env python3
"""Copy the phone's journal archives to this machine (APK 0.64, HANDOFF.md §75.1).

Context for the reader: a personal device; Damage's own phone app on the owner's home network.
From APK 0.64 the phone's flush journal rotates at a process start once it is past 64 MiB: the file
is set aside as `journal-YYYYMMDD-HHMMSS.jsonl` beside it and nothing is ever deleted there. An
uninstall clears the app's data, the archives with it, and the phone has no adb on this setup: the
copies this tool makes are the only ones that outlive that. PULL BEFORE AN UNINSTALL, WITH --live:
the lines since the last rotation (up to 64 MiB, the newest) are in the live file and in no archive.

    python3 tools/journal_pull.py                 # every archive -> ~/.damage/phone-journal/
    python3 tools/journal_pull.py --live          # ...then the live file too, as journal.jsonl there
    python3 tools/journal_pull.py --list          # print the listing, fetch nothing else
    python3 tools/journal_pull.py --host H --port P --dest DIR
    python3 tools/journal_pull.py --selftest      # the tool's own checks, against a stand-in on loopback only

It reads `token`, `phoneHost` and `replicaPort` from ~/.damage/config.json and never prints the token:
a line that would hold it (a host's reply that names the request it refused, a fault's text) is
printed with `<token>` in its place, and a token a URL cannot carry is refused before any request.
It GETs `/journals` first. A 404 there is a build before 0.64 (no listing; and one before 0.61 reads
a whole journal into memory when asked for it without `&tail=`, HANDOFF.md §71): the tool stops at
once and fetches NOTHING else, `--live` included.

For each archive in the listing: a local file of the same size is kept ("have it"); a missing or
smaller one is streamed in 64 KiB chunks to `NAME.part`, checked against the response's
Content-Length, then renamed over it; a LARGER local file is reported and left as it is (an
archive only ever grows — a second process on one data directory can append to it for a while —
so a larger local copy is not a copy of the phone's file). One line per file.

`--live` then copies the live file to `journal.jsonl`. The copy that is there from a pull before is
replaced only when it is an EARLIER STATE of the host's file — not longer, and its first 4 KiB are
the new copy's first bytes (the file only ever grows). Otherwise it is another file. When an archive
in the destination holds its lines (the phone rotated: its first 4 KiB are that archive's first bytes
and it is no longer than it) it is replaced all the same and the line names the archive (R2); else it
is kept, renamed `journal-live-<its modified time>.jsonl`, and the line says so (the app was installed
again, or the archive that holds it was not copied: then it is the only copy of those lines). Without `--live` the
live file's name and size are printed with NOT copied. A `--dest` that holds a `state.json` is a
Damage data directory with a journal of its own: refused, nothing requested.

No timer, no daemon, and no socket timeout: the operator supervises, and a pull that stalls shows
as a line that stops. A large archive shares the phone's radio with the glasses — pull when they rest.

Exit status: 0 every file asked for is here whole; 1 a shortfall (a short body, a reply that promises
no length, a refused request, a larger local file, a name that is not an archive name); 2 nothing
was pulled (no `/journals` route, the listing was refused or unreadable, the host did not answer in
HTTP, the command line, the config or its token was not understood, the destination is a Damage
data directory).
"""
import http.client, json, os, re, sys, time

CHUNK = 64 * 1024
HEAD = 4096                     # the first bytes that tell one append-only file from another (`set_aside`)
# the phone's own rule for an archive name (`Journal.isArchiveOf`): a name from the listing becomes a
# local file name, so anything else — a separator, a dot segment — is refused, never joined to a path
ARCHIVE = re.compile(r'[A-Za-z0-9_]+-\d{8}-\d{6}\.jsonl')
FLAGS = ('--host', '--port', '--dest', '--live', '--list', '--selftest', '--help', '-h')


def get(host, port, path):
    """One GET with no time bound. Returns (connection, response); the caller reads and closes."""
    c = http.client.HTTPConnection(host, port)
    c.request('GET', path)
    return c, c.getresponse()


def shown(body, token):
    """The start of a reply's body, for a line. The token is taken out BEFORE the cut to 200 characters: a
    reply that names the request it refused can otherwise end part-way through the token, and `main`'s wrapper
    only knows the whole one (RF-10)."""
    return body.decode('utf-8', 'replace').replace(token, '<token>')[:200].strip()


def listing(host, port, token, say):
    """The host's `/journals` as a dict, or an exit status when there is none to act on."""
    c, r = get(host, port, '/journals?token=' + token)
    try:
        body = r.read()
    finally:
        c.close()
    if r.status == 404:
        say('no /journals on %s:%d (404): a build before 0.64, or a host with no journal. Nothing was fetched.' % (host, port))
        say('(an older build is read with `curl ...&tail=N | journal_report.py -`; before 0.61 never without &tail=)')
        return 2
    if r.status != 200:
        say('the listing was refused: HTTP %d %s' % (r.status, shown(body, token)))
        return 2
    try:
        d = json.loads(body)
        # cast at the boundary: a name that is not text (a number, a list) would leave the tool as a fault
        d['archives'] = [(str(a['name']), int(a['bytes'])) for a in d['archives']]
        d['live'] = None if d.get('live') is None else (str(d['live']['name']), int(d['live']['bytes']))
        return d
    except (ValueError, KeyError, TypeError, OverflowError) as e:
        say('the listing is not what a 0.64 host sends (%s: %s)' % (type(e).__name__, e))
        return 2


def set_aside(final, part):
    """RF-5. `final` is the local copy of the LIVE journal, `part` the host's live file as it just arrived,
    whole. The file is append-only, so an earlier state of it is a prefix of a later one: a copy here that is
    longer than the new one, or whose first 4 KiB are not the new one's first bytes, is ANOTHER file (the
    phone rotated, or the app was installed again — and then this copy is the only one left of its lines),
    and it is renamed `journal-live-<its modified time>.jsonl` before the new copy takes its name. Size alone
    cannot tell: a new file outgrows the old copy. The new name is not an archive name to this tool (no
    listing can make it write over it) and is one to `journal_report.py`'s `journal-*.jsonl`; a name that is
    taken is never taken again. Returns what the caller's line says of the old copy, or None when it stays.
    A process that ends between this and the caller's rename leaves the old copy under its new name and the
    new one as `.part`: nothing is lost, and the next `--live` copies again.
    R2 item 29 (b): before that, the archives in the destination are asked — after an ordinary rotation the
    copy here is the head of the archive this pull has just brought (archives are copied first). When its
    first 4 KiB are an archive's first bytes and it is no longer than that archive, the archive holds every
    line of it: it is replaced with no set-aside (a kept copy would be a duplicate of up to 64 MiB, which the
    report's `journal-*.jsonl` reads twice) and the line names the archive."""
    if not os.path.isfile(final):
        return None
    size = os.path.getsize(final)
    with open(final, 'rb') as old, open(part, 'rb') as new:
        head = old.read(HEAD)
        if size <= os.path.getsize(part) and head == new.read(len(head)):
            return None
    here = os.path.dirname(final)
    for name in sorted(os.listdir(here)):
        archive = os.path.join(here, name)
        if not ARCHIVE.fullmatch(name) or not os.path.isfile(archive) or os.path.getsize(archive) < size:
            continue
        with open(archive, 'rb') as a:
            if a.read(len(head)) == head:
                return ('the copy that was here (%d B) is not an earlier state of this file; its lines are in %s '
                        '(no longer than it, and its first bytes), so it is replaced, not kept' % (size, name))
    stem, n = 'journal-live-' + time.strftime('%Y%m%d-%H%M%S', time.localtime(os.path.getmtime(final))), 1
    while True:
        name = '%s%s.jsonl' % (stem, '' if n == 1 else '-%d' % n)
        aside = os.path.join(os.path.dirname(final), name)
        if not os.path.lexists(aside):
            os.rename(final, aside)
            return 'the copy that was here (%d B) is not an earlier state of this file and is kept as %s' % (size, name)
        n += 1


def copy(host, port, token, path, final, say, label, live=False):
    """Streams one response body to `final` through `final`.part. True when it arrived whole. `live`: the
    file is the live journal, and a copy here that is not an earlier state of it is set aside, not replaced."""
    part = final + '.part'
    c, r = get(host, port, path)
    try:
        if r.status != 200:
            say('%s: HTTP %d %s' % (label, r.status, shown(r.read(4096), token)))
            return False
        length = r.getheader('Content-Length')
        if length is None or not re.fullmatch(r'\s*[0-9]+\s*', length):
            # said as a line, through `say`: `int()` of it is a fault whose text quotes the header — a host's
            # bytes, which can name the request — and a fault that leaves `main` passes no wrapper (RF-10)
            say('%s: the reply promises no length that is a number (Content-Length: %r); not copied' % (label, length))
            return False
        want = int(length)
        got = 0
        with open(part, 'wb') as f:
            while True:
                try:
                    b = r.read(CHUNK)
                except http.client.IncompleteRead as e:      # the body ended before its promised length
                    b = e.partial
                    f.write(b); got += len(b)
                    break
                if not b:
                    break
                f.write(b); got += len(b)
        if got != want:
            say('%s: SHORT, %d of the %d B promised; left as %s' % (label, got, want, os.path.basename(part)))
            return False
        aside = set_aside(final, part) if live else None
        os.replace(part, final)
        say('%s: copied, %d B%s' % (label, got, '' if aside is None else '; ' + aside))
        return True
    finally:
        c.close()


def pull(host, port, token, dest, live=False, list_only=False, say=print):
    if not list_only and os.path.isfile(os.path.join(dest, 'state.json')):
        # RF-5, the second site: that directory's journal.jsonl and archives are its own, not the host's
        say('%s holds a state.json: it is a Damage data directory, with a journal of its own. Nothing was '
            'requested; give --dest a directory of its own.' % dest)
        return 2
    d = listing(host, port, token, say)
    if isinstance(d, int):
        return d
    if list_only:
        for name, size in d['archives']:
            say('%s  %d B' % (name, size))
        say('live: ' + ('none' if d['live'] is None else '%s  %d B' % d['live']))
        return 0
    os.makedirs(dest, exist_ok=True)
    short = 0
    for name, size in d['archives']:
        if not ARCHIVE.fullmatch(name):
            say('%r: not an archive name; not fetched' % name)
            short += 1
            continue
        final = os.path.join(dest, name)
        have = os.path.getsize(final) if os.path.isfile(final) else -1
        if have == size:
            say('%s: have it, %d B' % (name, size))
        elif have > size:
            say('%s: LARGER here (%d B) than on the host (%d B); left as it is' % (name, have, size))
            short += 1
        elif not copy(host, port, token, '/journal?token=%s&name=%s' % (token, name), final, say, name):
            short += 1
    if live:
        if d['live'] is None:
            say('live: the host has no live journal file')
        elif not copy(host, port, token, '/journal?token=' + token, os.path.join(dest, 'journal.jsonl'), say, 'journal.jsonl (live)', live=True):
            short += 1
    if not d['archives'] and not live:
        say('no archives on the host yet (the journal rotates at a process start past 64 MiB)')
    if not live and d['live'] is not None:
        # RF-N1: the newest lines, up to 64 MiB of them, are in that file and nowhere in what was just copied
        say('live: %s, %d B on the host, NOT copied (add --live; an uninstall clears it)' % d['live'])
    return 1 if short else 0


def main(argv, config=None, say=print):
    """`config` stands in for ~/.damage/config.json in the self-test."""
    if '--help' in argv or '-h' in argv:
        say(__doc__)
        return 0
    opts, i = {}, 0
    while i < len(argv):
        a = argv[i]
        if a in ('--host', '--port', '--dest') and i + 1 < len(argv):
            opts[a] = argv[i + 1]
            i += 2
        elif a in ('--live', '--list'):
            opts[a] = True
            i += 1
        else:
            say('unknown argument: %s (flags: %s)' % (a, ' '.join(FLAGS)))
            return 2
    if config is None:
        try:
            config = json.load(open(os.path.expanduser('~/.damage/config.json')))
        except (OSError, ValueError) as e:
            say('~/.damage/config.json could not be read (%s)' % type(e).__name__)
            return 2
    token = config.get('token')
    if not token:
        say('the config holds no token')
        return 2
    if not isinstance(token, str) or re.search(r'[^!-~]', token):
        # RF-10: a request line cannot carry it, and the fault that says so would quote the path — token
        # and all, in the escaped spelling for a control character, which no replace of the token finds
        say('the config\'s token is not text a URL can carry (a space, a control character or one outside '
            'ASCII is in it): nothing was requested')
        return 2
    try:
        port = int(opts.get('--port', config.get('replicaPort', 7403)))
    except ValueError:
        say('--port takes a number')
        return 2
    host = opts.get('--host', config.get('phoneHost', 'aphone'))
    dest = opts.get('--dest', os.path.expanduser('~/.damage/phone-journal'))
    # RF-10: from here on no line leaves with the token in it, whatever a host or a fault's text put there
    plain = say
    # (the token as written, and as `%r` writes it: one line below prints a host's name that way)
    say = lambda line: plain(line.replace(token, '<token>').replace(repr(token)[1:-1], '<token>'))
    try:
        return pull(host, port, token, dest, live='--live' in opts, list_only='--list' in opts, say=say)
    except (OSError, http.client.HTTPException) as e:
        say('%s:%d: %s: %s' % (host, port, type(e).__name__, e))
        return 2


def selftest():
    """The tool against a stand-in for the host on 127.0.0.1 (an ephemeral port) and scratch
    directories under the system temp directory. The phone is not contacted, ~/.damage is neither
    read nor written, and each case names what the tool has to do."""
    import http.server, shutil, tempfile, threading, time, urllib.parse
    # synthetic, and in capitals: the cases look for its first ten characters in every line, and no word of
    # the tool and no scratch path (the temp directory's random letters are lower case) may hold those
    TOKEN = 'SYNTHETIC-TOKEN-5f3a9c'
    A1, A2 = 'journal-20261110-213045.jsonl', 'journal-20261111-080000.jsonl'
    state = {}

    class Host(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass

        def send(self, code, body, promised=None):
            self.send_response(code)
            if promised != 'nothing':
                self.send_header('Content-Length', str(len(body) if promised is None else promised))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            q = dict(urllib.parse.parse_qsl(u.query))
            state['seen'].append(u.path + ('?name=' + q['name'] if 'name' in q else ''))
            if state['garbage']:                    # another program on that port: no HTTP status line at all,
                self.wfile.write(('220 not an HTTP server; it was asked %s\r\n\r\n' % self.path).encode())
                return                              # and its line names what it was asked — the token in it
            if state['echo'] == u.path:             # a proxy's error page: it names the request it refused
                return self.send(403, b'.' * state['pad'] + ('Forbidden: GET %s' % self.path).encode())
            if q.get('token') != state['accept']:
                return self.send(403, b'token required')
            if u.path == '/journals':
                if state['old']:
                    return self.send(404, b'not found')
                return self.send(200, json.dumps({
                    'live': None if state['live'] is None else {'name': 'journal.jsonl', 'bytes': len(state['live'])},
                    'archives': [{'name': n, 'bytes': len(b) if state['size'] is None else state['size'], 'modified': 1}
                                 for n, b in state['archives']]}).encode())
            if u.path == '/journal':
                body = dict(state['archives']).get(q['name']) if 'name' in q else state['live']
                if body is None:
                    return self.send(404, b'no journal')
                if 'name' in q and q['name'] == state['short']:
                    return self.send(200, body[:len(body) // 2], promised=len(body))
                if state['length'] is not None:     # a host whose length header is absent, or is not a number
                    return self.send(200, body, promised='nothing' if state['length'] == 'absent' else self.path)
                return self.send(200, body)
            self.send(404, b'not found')

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Host)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_address[1]
    root = tempfile.mkdtemp(prefix='journal-pull-selftest-')
    big = b'{"n":1}\n' * 30000                    # past several 64 KiB chunks
    said, missed, cases, tokens = [], 0, 0, set()

    def run(args, old=False, live=b'{"live":1}\n', archives=((A1, big), (A2, b'{"second":1}\n')), short=None, token=TOKEN,
            echo=None, pad=0, garbage=False, accept=TOKEN, length=None, size=None):
        state.update(seen=[], old=old, live=live, archives=list(archives), short=short, echo=echo, pad=pad, garbage=garbage,
                     accept=accept, length=length, size=size)
        tokens.add(token)
        out = []
        try:
            code = main(args + ['--host', '127.0.0.1', '--port', str(port)], config={'token': token}, say=out.append)
        except Exception as e:                      # its text can hold the request: only its type is said here
            code = 'main raised %s' % type(e).__name__
        said.extend(out)
        return code, out, list(state['seen'])

    def check(title, notes):
        nonlocal missed, cases
        cases += 1
        missed += bool(notes)
        print('%s  %s%s' % ('FAIL' if notes else 'ok  ', title, ''.join('\n        ' + n for n in notes)))

    def read(d, name):
        p = os.path.join(d, name)
        return open(p, 'rb').read() if os.path.isfile(p) else None

    try:
        d = os.path.join(root, 'old')
        code, out, seen = run(['--dest', d, '--live'], old=True)
        check('a /journals 404 (a build before 0.64) stops before any other fetch, --live included',
              [n for n in ['exit %d, not 2' % code if code != 2 else '', 'requests: %r' % seen if seen != ['/journals'] else '',
                           'the destination was made' if os.path.exists(d) else ''] if n])

        d = os.path.join(root, 'fresh')
        code, out, seen = run(['--dest', d, '--live'])
        check('a first pull copies both archives and the live file whole, through .part',
              [n for n in ['exit %d' % code if code else '', 'A1 differs' if read(d, A1) != big else '',
                           'A2 differs' if read(d, A2) != b'{"second":1}\n' else '', 'live differs' if read(d, 'journal.jsonl') != b'{"live":1}\n' else '',
                           'left: %r' % sorted(os.listdir(d)) if sorted(os.listdir(d)) != [A1, A2, 'journal.jsonl'] else ''] if n])

        code, out, seen = run(['--dest', d])
        check('a second pull keeps what is here and fetches no archive',
              [n for n in ['exit %d' % code if code else '', 'requests: %r' % seen if seen != ['/journals'] else '',
                           'said %r' % out if sum('have it' in l for l in out) != 2 else ''] if n])

        # (the smaller file here is NOT the archive's first bytes: an archive's name is its identity, so it is
        #  replaced all the same and never set aside — only the live file is, whose name says nothing)
        open(os.path.join(d, A1), 'wb').write(b'{"not":"the first bytes of that archive"}\n' * 20)
        open(os.path.join(d, A2), 'wb').write(b'{"second":1}\n and more than the host holds')
        code, out, seen = run(['--dest', d])
        check('a smaller local file is fetched again whole (and not set aside); a larger one is reported and left; exit 1',
              [n for n in ['exit %d, not 1' % code if code != 1 else '', 'A1 not whole' if read(d, A1) != big else '',
                           'A2 was replaced' if read(d, A2) != b'{"second":1}\n and more than the host holds' else '',
                           'said %r' % out if not any('LARGER here' in l for l in out) else '',
                           'left: %r' % sorted(os.listdir(d)) if sorted(os.listdir(d)) != [A1, A2, 'journal.jsonl'] else '',
                           'requests: %r' % seen if seen != ['/journals', '/journal?name=' + A1] else ''] if n])

        d = os.path.join(root, 'short')
        code, out, seen = run(['--dest', d], short=A1)
        check('a body shorter than its Content-Length stays a .part and the exit status is 1',
              [n for n in ['exit %d, not 1' % code if code != 1 else '', 'the short file took the final name' if read(d, A1) is not None else '',
                           'no .part kept' if read(d, A1 + '.part') != big[:len(big) // 2] else '',
                           'the other archive was not copied' if read(d, A2) != b'{"second":1}\n' else '',
                           'said %r' % out if not any('SHORT' in l for l in out) else ''] if n])

        d = os.path.join(root, 'names')
        code, out, seen = run(['--dest', d], archives=(('../escaped.jsonl', b'x'), ('journal.jsonl', b'y'), (A2, b'z')))
        check('a listed name that is not an archive name is never fetched or joined to a path',
              [n for n in ['exit %d, not 1' % code if code != 1 else '', 'requests: %r' % seen if seen != ['/journals', '/journal?name=' + A2] else '',
                           'a file outside the destination' if os.path.exists(os.path.join(root, 'escaped.jsonl')) else '',
                           'left: %r' % sorted(os.listdir(d)) if sorted(os.listdir(d)) != [A2] else ''] if n])

        d = os.path.join(root, 'list')
        code, out, seen = run(['--dest', d, '--list'])
        check('--list prints the listing and fetches nothing else',
              [n for n in ['exit %d' % code if code else '', 'requests: %r' % seen if seen != ['/journals'] else '',
                           'said %r' % out if out != ['%s  %d B' % (A1, len(big)), '%s  13 B' % A2, 'live: journal.jsonl  11 B'] else '',
                           'the destination was made' if os.path.exists(d) else ''] if n])

        d = os.path.join(root, 'nolive')
        code, out, seen = run(['--dest', d, '--live'], live=None, archives=())
        check('a host with no live file says so; that is not a shortfall',
              [n for n in ['exit %d' % code if code else '', 'said %r' % out if out != ['live: the host has no live journal file'] else ''] if n])

        code, out, seen = run(['--dest', os.path.join(root, 'refused')], token='not-the-token')
        check('a refused listing ends with status 2 and fetches nothing else',
              [n for n in ['exit %d, not 2' % code if code != 2 else '', 'requests: %r' % seen if seen != ['/journals'] else ''] if n])

        code, out, seen = run(['--dest', d, '--lst'])
        check('an unknown flag is refused with status 2 and nothing is fetched',
              [n for n in ['exit %d, not 2' % code if code != 2 else '', 'requests: %r' % seen if seen else ''] if n])

        # RF-5: the live copy here is replaced only by a later state of the same file
        before = b'{"t":1,"before":"the uninstall"}\n' * 40
        aside = re.compile(r'journal-live-\d{8}-\d{6}(-\d+)?\.jsonl')

        def live_twice(name, second, at=None):
            """A `--live` pull of `before`, then one of `second`, into one destination. `at`: the first copy's
            modified time. Returns (the destination, the second pull's exit status and lines)."""
            d = os.path.join(root, name)
            run(['--dest', d, '--live'], live=before, archives=())
            if at is not None:
                os.utime(os.path.join(d, 'journal.jsonl'), (at, at))
            code, out, seen = run(['--dest', d, '--live'], live=second, archives=())
            return d, code, out

        def kept(d, out, second):
            """What must hold after a pull that met a copy which is not an earlier state of the host's file."""
            others = [n for n in sorted(os.listdir(d)) if n != 'journal.jsonl']
            return [n for n in ['the new copy is not journal.jsonl' if read(d, 'journal.jsonl') != second else '',
                                'the copy that was here is in no file of the destination: %r' % others if not any(read(d, n) == before for n in others) else '',
                                'left: %r' % others if not (len(others) == 1 and aside.fullmatch(others[0])) else '',
                                'no line names the kept file: %r' % out if not any('kept as ' + (others or ['?'])[0] in l for l in out) else ''] if n]

        d, code, out = live_twice('live-smaller', b'{"t":9,"after":"a reinstall"}\n')
        check('RF-5: a live file SMALLER than the copy here (a reinstall): the copy here keeps a name of its own',
              ['exit %r' % code if code else ''][:code != 0] + kept(d, out, b'{"t":9,"after":"a reinstall"}\n'))

        grown = b'{"t":9,"after":"a reinstall"}\n' * 400
        d, code, out = live_twice('live-outgrown', grown)
        check('RF-5: a live file that has OUTGROWN the copy here and starts with another line: the same',
              ['exit %r' % code if code else ''][:code != 0] + kept(d, out, grown))

        d, code, out = live_twice('live-grew', before + b'{"t":2,"more":1}\n')
        check('RF-5: a live file that only grew replaces the copy here; nothing is set aside',
              [n for n in ['exit %r' % code if code else '', 'left: %r' % sorted(os.listdir(d)) if sorted(os.listdir(d)) != ['journal.jsonl'] else '',
                           'journal.jsonl is not the grown file' if read(d, 'journal.jsonl') != before + b'{"t":2,"more":1}\n' else '',
                           'said %r' % out if any('kept as' in l for l in out) else ''] if n])

        long_before = before * 200                               # 6,600 B: past the 4 KiB that are compared
        d = os.path.join(root, 'live-shorter')
        run(['--dest', d, '--live'], live=long_before, archives=())
        code, out, seen = run(['--dest', d, '--live'], live=long_before[:5000], archives=())
        others = [n for n in sorted(os.listdir(d)) if n != 'journal.jsonl']
        check('RF-5: a host file SHORTER than the copy here with the same first 4 KiB is another file too (it cannot have shrunk)',
              [n for n in ['exit %r' % code if code else '', 'the new copy is not journal.jsonl' if read(d, 'journal.jsonl') != long_before[:5000] else '',
                           'the longer copy that was here is in no file: %r' % others if not any(read(d, n) == long_before for n in others) else ''] if n])

        # R2 item 29 (b): after a rotation the copy here is the head of the archive the pull just brought; an
        # archive that holds its lines makes a set-aside copy a duplicate (up to 64 MiB, read twice by the
        # report's `journal-*.jsonl`)
        d = os.path.join(root, 'live-rotated')
        run(['--dest', d, '--live'], live=before, archives=())
        fresh = b'{"t":9,"fresh":"after the rotation"}\n'
        code, out, seen = run(['--dest', d, '--live'], live=fresh, archives=((A1, before + b'{"t":3,"later":1}\n'),))
        check('R2: a copy here whose lines an archive in the destination holds is replaced, not set aside, and the '
              'line names that archive',
              [n for n in ['exit %r' % code if code else '', 'left: %r' % sorted(os.listdir(d)) if sorted(os.listdir(d)) != [A1, 'journal.jsonl'] else '',
                           'journal.jsonl is not the new live file' if read(d, 'journal.jsonl') != fresh else '',
                           'no line names %s as holding the lines: %r' % (A1, out) if not any('its lines are in ' + A1 in l for l in out) else ''] if n])
        d = os.path.join(root, 'live-not-held')
        run(['--dest', d, '--live'], live=before, archives=())
        code, out, seen = run(['--dest', d, '--live'], live=fresh, archives=((A1, b'{"t":0,"another":"file"}\n' + before),))
        others = [n for n in sorted(os.listdir(d)) if n not in ('journal.jsonl', A1)]
        check('R2: ...an archive that does NOT begin with the copy here holds nothing of it: the copy is set aside as before',
              [n for n in ['exit %r' % code if code else '', 'left: %r' % others if not (len(others) == 1 and aside.fullmatch(others[0])) else '',
                           'the copy that was here is not kept: %r' % others if not others or read(d, others[0]) != before else ''] if n])

        d = os.path.join(root, 'live-taken')
        os.makedirs(d)
        taken = time.strftime('journal-live-%Y%m%d-%H%M%S.jsonl', time.localtime(1794000000))
        open(os.path.join(d, taken), 'wb').write(b'{"kept":"by a pull before"}\n')
        d, code, out = live_twice('live-taken', b'{"t":9,"after":"a reinstall"}\n', at=1794000000)
        second = taken[:-len('.jsonl')] + '-2.jsonl'
        check('RF-5: a name a kept copy already has is not taken again (two copies of one modified time)',
              [n for n in ['exit %r' % code if code else '', 'the copy kept before was replaced' if read(d, taken) != b'{"kept":"by a pull before"}\n' else '',
                           'the copy that was here is not %s: %r' % (second, sorted(os.listdir(d))) if read(d, second) != before else '',
                           'no line names %s: %r' % (second, out) if not any('kept as ' + second in l for l in out) else ''] if n])

        d = os.path.join(root, 'datadir')
        os.makedirs(d)
        open(os.path.join(d, 'state.json'), 'wb').write(b'{}')
        open(os.path.join(d, 'journal.jsonl'), 'wb').write(b'{"this":"machine\'s own journal"}\n')
        code, out, seen = run(['--dest', d, '--live'])
        code2, out2, seen2 = run(['--dest', d, '--list'])
        check('RF-5: a destination that holds a state.json (a Damage data directory) is refused before any request; '
              '--list, which writes nothing, is not',
              [n for n in ['exit %r, not 2' % code if code != 2 else '', 'requests: %r' % seen if seen else '',
                           'its own journal.jsonl was replaced' if read(d, 'journal.jsonl') != b'{"this":"machine\'s own journal"}\n' else '',
                           'left: %r' % sorted(os.listdir(d)) if sorted(os.listdir(d)) != ['journal.jsonl', 'state.json'] else '',
                           'said %r' % out if not any('state.json' in l for l in out) else '',
                           '--list: exit %r, requests %r' % (code2, seen2) if code2 != 0 or seen2 != ['/journals'] else ''] if n])

        # RF-N1: the pull by the first usage line leaves the live file on the host
        d = os.path.join(root, 'notlive')
        code, out, seen = run(['--dest', d])
        want = 'live: journal.jsonl, 11 B on the host, NOT copied (add --live; an uninstall clears it)'
        code2, out2, seen2 = run(['--dest', d], live=None)
        check('RF-N1: a pull without --live says that the live file was not copied (and nothing of one the host does not have)',
              [n for n in ['exit %r' % code if code else '', 'said %r, without the line %r' % (out, want) if want not in out else '',
                           'a live file was fetched: %r' % seen if '/journal' in seen else '',
                           'a host with no live file: exit %r' % code2 if code2 else '',
                           'said %r of a host with no live file' % out2 if any('NOT copied' in l for l in out2) else ''] if n])

        # RF-10: nothing the host sends back, whole or cut, and no fault's text puts the token in a line
        def token_in(lines, token=TOKEN):
            """The lines that hold the token: whole, as Python would escape it, or its first ten characters."""
            return ['printed: %r' % l.replace(token, '<the whole token>') for l in lines
                    if token in l or repr(token)[1:-1] in l or token[:10] in l]

        d = os.path.join(root, 'echo')
        code, out, seen = run(['--dest', d, '--list'], echo='/journals')
        code2, out2, seen2 = run(['--dest', d], echo='/journal')
        check('RF-10: a refusal that names the request it refused (the listing\'s, an archive\'s): the token is in no line',
              [n for n in ['exit %r, not 2' % code if code != 2 else '', 'exit %r, not 1' % code2 if code2 != 1 else '',
                           'said %r' % [l.replace(TOKEN, '<the whole token>') for l in out] if not any('token=<token>' in l for l in out) else '',
                           'said %r' % [l.replace(TOKEN, '<the whole token>') for l in out2] if sum('token=<token>' in l for l in out2) != 2 else ''] if n]
              + token_in(out + out2))

        # 200 characters of a reply are shown: the padding puts that cut ten characters into the token
        code, out, seen = run(['--dest', d, '--list'], echo='/journals', pad=200 - len('Forbidden: GET /journals?token=') - 10)
        code2, out2, seen2 = run(['--dest', d], echo='/journal', pad=200 - len('Forbidden: GET /journal?token=') - 10)
        check('RF-10: ...also when the shown part of the reply ends inside the token',
              [n for n in ['exit %r, not 2' % code if code != 2 else '', 'exit %r, not 1' % code2 if code2 != 1 else '',
                           'said %r' % out if not any('Forbidden: GET' in l for l in out) else ''] if n] + token_in(out + out2))

        notes = []
        for kind, odd in (('a space', 'SELFTEST TOKEN'), ('a tab', 'SELFTEST\tTOKEN'), ('a letter outside ASCII', 'SELFTEST\u00e9TOKEN')):
            code, out, seen = run(['--dest', d, '--list'], token=odd)
            notes += [n for n in ['%s: exit %r, not 2' % (kind, code) if code != 2 else '', '%s: requests: %r' % (kind, seen) if seen else '',
                                  '%s: said %r' % (kind, out) if not any('token' in l for l in out) else ''] if n] + token_in(out, odd)
        check('RF-10: a token a URL cannot carry (a space, a tab, a letter outside ASCII) is refused before any request, and is in no line', notes)

        odd = 'SELFTEST\\TOKEN'                                   # one backslash: Python's %r writes two
        code, out, seen = run(['--dest', d], token=odd, accept=odd, archives=((odd + '.jsonl', b'x'),))
        code2, out2, seen2 = run(['--dest', d, '--list'], token=odd, accept=odd, archives=((odd + '.jsonl', b'x'),))
        check('RF-10: a host\'s text that holds the token is printed without it, as written (%s) and as Python escapes it (%r)',
              [n for n in ['exit %r, not 1' % code if code != 1 else '', 'said %r' % out if not any('not an archive name' in l and '<token>' in l for l in out) else '',
                           'exit %r' % code2 if code2 else '', 'said %r' % out2 if '<token>.jsonl  1 B' not in out2 else ''] if n]
              + token_in(out + out2, odd))

        code, out, seen = run(['--dest', d, '--list'], garbage=True)
        check('RF-10: a host that does not answer in HTTP is one line and status 2, not a fault that leaves the tool; '
              'the fault\'s text names the request, and the token is not in the line',
              [n for n in ['exit %r, not 2' % code if code != 2 else '',
                           'said %r' % [l.replace(TOKEN, '<the whole token>') for l in out]
                           if len(out) != 1 or 'BadStatusLine' not in out[0] or 'token=<token>' not in out[0] else ''] if n] + token_in(out))

        # a reply that is not what the tool expects must end as a line through `say`, never as a Python fault:
        # a fault's text quotes what the host sent (here the request, token and all) and no wrapper is around it
        d = os.path.join(root, 'length')
        code, out, seen = run(['--dest', d, '--live'], archives=((A2, b'{"second":1}\n'),), length='echo')
        code2, out2, seen2 = run(['--dest', d, '--live'], archives=((A2, b'{"second":1}\n'),), length='absent')
        written = sorted(os.listdir(d)) if os.path.isdir(d) else []
        code3, out3, seen3 = run(['--dest', d], archives=((5, b'x'), (A2, b'{"second":1}\n')))
        code4, out4, seen4 = run(['--dest', d], size=float('inf'))          # JSON `Infinity`: no int() of it
        check('a reply whose Content-Length is no number (it holds the request) or is absent, a listed name that is '
              'no text and a listed size that is no size: each is a line, no fault leaves the tool and the token is in no line',
              [n for n in ['no number: exit %r, not 1' % code if code != 1 else '',
                           'no number: said %r' % [l.replace(TOKEN, '<the whole token>') for l in out] if sum('Content-Length' in l for l in out) != 2 else '',
                           'absent: exit %r, not 1' % code2 if code2 != 1 else '',
                           'absent: said %r' % out2 if sum('Content-Length' in l for l in out2) != 2 else '',
                           'a file was written for a reply of no known length: %r' % written if written else '',
                           'a name that is a number: exit %r, not 1' % code3 if code3 != 1 else '',
                           'a name that is a number: said %r' % out3 if not any('not an archive name' in l for l in out3) else '',
                           'the archive beside it was not copied' if read(d, A2) != b'{"second":1}\n' else '',
                           'a size that is Infinity: exit %r, not 2' % code4 if code4 != 2 else '',
                           'a size that is Infinity: said %r' % out4 if not any('not what a 0.64 host sends' in l for l in out4) else '',
                           'a size that is Infinity: requests %r' % seen4 if seen4 != ['/journals'] else ''] if n]
              + token_in(out + out2 + out3 + out4))

        check('the token is in no line the tool printed', [n for t in sorted(tokens | {'not-the-token'}) for n in token_in(said, t)])
    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(root, ignore_errors=True)
    print('selftest: %d of %d' % (cases - missed, cases))
    print('not covered here: the phone itself (this stage does not contact it), and a pull that stalls (no time bound, by design)')
    return 1 if missed else 0


if __name__ == '__main__':
    sys.exit(selftest() if sys.argv[1:] == ['--selftest'] else main(sys.argv[1:]))
