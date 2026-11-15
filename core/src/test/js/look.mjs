// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package JW0 (S1-2-decisions.md C): pictures of every page and state in headless Firefox, at a phone's
// width and a desk's, against the stand-in only. Not a test (node --test does not run it):
//   node core/src/test/js/look.mjs OUT_DIR [scene ...] [--dark]
// Each picture gets a new empty profile under OUT_DIR, removed after. The profile sends Firefox's own traffic to a
// loopback port that counts and refuses it, so no request leaves this machine; the count is printed. DISPLAY is
// removed from Firefox's environment: no window can open on the desktop. A Firefox still running after LOOK_WAIT_MS
// (90 s) is ended (the harness supervises its child; nothing in the product waits on it) and its picture said missing.
// LOOK_SIZES=phone (or desk) takes one width; LOOK_DEBUG=1 lists the requests the stand-in served.

import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:net';
import { join, resolve } from 'node:path';
import { standin } from './standin.mjs';
import { fixtures, SCENES } from './fixtures.mjs';

const args = process.argv.slice(2);
const dark = args.includes('--dark');
const [out, ...names] = args.filter((a) => a !== '--dark');
if (!out) throw new Error('usage: look.mjs OUT_DIR [scene ...] [--dark]');
const SIZES = { phone: [390, 844], desk: [1440, 900] };
const WAIT_MS = Number(process.env.LOOK_WAIT_MS || 90000);

const outside = [];
const hole = createServer((s) => {
  s.on('error', () => {}); // a refused client resetting is the point of the hole
  s.once('data', (d) => {
    outside.push(String(d).split('\r\n')[0]);
    s.destroy();
  });
});
await new Promise((ok) => hole.listen(0, '127.0.0.1', ok));
const HOLE = hole.address().port;

function userJs() {
  const prefs = {
    'network.proxy.type': 1, 'network.proxy.http': '127.0.0.1', 'network.proxy.http_port': HOLE, 'network.proxy.ssl': '127.0.0.1',
    'network.proxy.ssl_port': HOLE, 'network.proxy.socks': '127.0.0.1', 'network.proxy.socks_port': HOLE, 'network.proxy.allow_hijacking_localhost': false,
    'network.http.http3.enable': false, 'network.trr.mode': 5, 'network.dns.disablePrefetch': true, 'network.prefetch-next': false,
    'network.captive-portal-service.enabled': false, 'network.connectivity-service.enabled': false, 'dom.push.connection.enabled': false,
    'services.settings.server': `http://127.0.0.1:${HOLE}/`, 'browser.region.network.url': '', 'app.normandy.enabled': false,
    'browser.safebrowsing.malware.enabled': false, 'browser.safebrowsing.phishing.enabled': false, 'browser.safebrowsing.downloads.enabled': false,
    'browser.safebrowsing.update.enabled': false, 'datareporting.healthreport.uploadEnabled': false, 'datareporting.policy.dataSubmissionEnabled': false,
    'toolkit.telemetry.enabled': false, 'extensions.update.enabled': false, 'app.update.auto': false, 'ui.systemUsesDarkTheme': dark ? 1 : 0,
  };
  return Object.entries(prefs).map(([k, v]) => `user_pref(${JSON.stringify(k)}, ${JSON.stringify(v)});`).join('\n') + '\n';
}

async function picture(name, size) {
  const scene = SCENES[name];
  const srv = await standin(scene, fixtures);
  const profile = resolve(out, `profile-${name}-${size}`);
  const file = resolve(out, `${name}-${size}${dark ? '-dark' : ''}.png`);
  rmSync(file, { force: true });
  mkdirSync(profile, { recursive: true });
  writeFileSync(join(profile, 'user.js'), userJs());
  const env = { ...process.env, MOZ_HEADLESS: '1', MOZ_NO_REMOTE: '1' };
  delete env.DISPLAY;
  delete env.WAYLAND_DISPLAY;
  const ff = spawn('firefox', ['--headless', '--no-remote', '--profile', profile, '--window-size', SIZES[size].join(','),
    '--screenshot', file, `http://127.0.0.1:${srv.port}${scene.path}`], { env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let said = '';
  ff.stdout.on('data', (d) => { said += d; });
  ff.stderr.on('data', (d) => { said += d; });
  let ended = false;
  const watch = setTimeout(() => { ended = true; process.kill(-ff.pid, 'SIGKILL'); }, WAIT_MS);
  const code = await new Promise((ok) => ff.on('exit', (c) => ok(c)));
  clearTimeout(watch);
  await srv.close();
  const left = (() => { try { return execFileSync('pgrep', ['-f', profile]).toString().trim(); } catch (e) { return ''; } })();
  if (left) process.kill(-ff.pid, 'SIGKILL');
  rmSync(profile, { recursive: true, force: true });
  const ok = existsSync(file);
  console.log(`${ok ? 'took' : 'MISSING'} ${file}${ended ? ` (Firefox ended after ${WAIT_MS / 1000} s: the page never reached its state)` : ''} · exit ${code}` +
    `${left ? ` · left processes ended: ${left.split('\n').join(' ')}` : ''} · the page sent ${srv.log.filter((m) => m.t !== 'note').map((m) => m.t + (m.scope ? ` ${m.scope}` : '')).join(', ')}`);
  for (const m of srv.log) if (m.t === 'note' && (!ok || /error/.test(m.text))) console.log(`  ${m.text}`);
  if (!ok || /error|unrecognized/i.test(said)) console.log(`  Firefox said: ${said.trim().split('\n').slice(-6).join(' | ')}`);
}

mkdirSync(out, { recursive: true });
for (const name of names.length ? names : Object.keys(SCENES)) for (const size of (process.env.LOOK_SIZES || 'phone,desk').split(',')) await picture(name, size);
hole.close();
console.log(outside.length ? `Firefox tried ${outside.length} outside requests, all refused at the stand-in: ${[...new Set(outside)].join(' | ')}` : 'Firefox tried no outside request.');
