#!/usr/bin/env node
// Orbit protocol test sender (Node 18+, no dependencies).
//
//   node tools/orbit-send.mjs --to 192.168.1.20:47717 --key ABCD2345 file1 file2 ...
//     --drop-at <bytes>   abort the TCP connection after N bytes of the first file, then resume via offset
//     --shortcut          use the single-request multipart endpoint (like the iOS Shortcut)
//
// Prints SHA-256 of each sent file so it can be compared with the received copy.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); if (i < 0) return undefined; const v = args[i + 1]; args.splice(i, 2); return v; };
const flag = (name) => { const i = args.indexOf(name); if (i < 0) return false; args.splice(i, 1); return true; };
const to = opt('--to'); const key = opt('--key'); const dropAt = Number(opt('--drop-at') ?? 0); const shortcut = flag('--shortcut');
const files = args;
if (!to || !key || files.length === 0) { console.error('usage: orbit-send.mjs --to host:port --key KEY [--drop-at N] [--shortcut] files...'); process.exit(2); }
const [host, port] = to.split(':');

function request(method, p, { body, headers = {}, stream, abortAfter } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port: Number(port), method, path: p, headers: { 'X-Orbit-Key': key, ...headers } }, res => {
      let data = ''; res.setEncoding('utf8'); res.on('data', c => data += c);
      res.on('end', () => { let json = null; try { json = JSON.parse(data); } catch {} resolve({ status: res.statusCode, json, text: data }); });
    });
    req.on('error', reject);
    if (stream) {
      let sent = 0;
      stream.on('data', chunk => {
        if (abortAfter && sent + chunk.length >= abortAfter) { req.write(chunk.subarray(0, abortAfter - sent)); stream.destroy(); setTimeout(() => req.destroy(new Error('simulated drop')), 200); return; }
        sent += chunk.length;
        if (!req.write(chunk)) { stream.pause(); req.once('drain', () => stream.resume()); }
      });
      stream.on('end', () => req.end());
    } else req.end(body);
  });
}

const sha256 = f => new Promise(r => { const h = crypto.createHash('sha256'); fs.createReadStream(f).on('data', d => h.update(d)).on('end', () => r(h.digest('hex'))); });
const fmt = b => b > 1e9 ? (b / 1e9).toFixed(2) + ' GB' : (b / 1e6).toFixed(1) + ' MB';

async function viaSessions() {
  const list = files.map(f => ({ name: path.basename(f), size: fs.statSync(f).size }));
  let s = await request('POST', '/api/v1/sessions', { body: JSON.stringify({ sender: 'orbit-send', files: list }), headers: { 'Content-Type': 'application/json' } });
  if (s.status !== 200) throw new Error(`create: ${s.status} ${s.text}`);
  const id = s.json.sessionId;
  console.log(`session ${id}: ${s.json.status}`);
  while (s.json.status === 'pending') { await new Promise(r => setTimeout(r, 1000)); s = await request('GET', `/api/v1/sessions/${id}`); process.stdout.write('.'); }
  if (!['accepted', 'active'].includes(s.json.status)) throw new Error('not accepted: ' + s.json.status);

  for (let i = 0; i < files.length; i++) {
    const f = files[i]; const size = list[i].size; const t0 = Date.now();
    let offset = 0; let attempt = 0;
    for (;;) {
      try {
        const drop = i === 0 && attempt === 0 && dropAt > 0 ? dropAt : 0;
        const r = await request('PUT', `/api/v1/sessions/${id}/files/${i}?offset=${offset}`, {
          stream: fs.createReadStream(f, { start: offset, highWaterMark: 1 << 20 }), abortAfter: drop,
          headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': size - offset },
        });
        if (r.status === 200) { console.log(`\n${list[i].name}: done → ${r.json.savedAs} in ${((Date.now() - t0) / 1000).toFixed(1)}s (${fmt(size / ((Date.now() - t0) / 1000))}/s)`); break; }
        throw Object.assign(new Error(`${r.status} ${r.text}`), { http: r.status });
      } catch (e) {
        if (e.http && e.http !== 409 && e.http !== 400) throw e;
        if (++attempt > 5) throw e;
        await new Promise(r => setTimeout(r, 800));
        const st = await request('GET', `/api/v1/sessions/${id}/files/${i}`);
        offset = st.json.received;
        console.log(`\n${list[i].name}: interrupted (${e.message}); resuming at ${fmt(offset)}`);
      }
    }
  }
}

async function viaShortcut() {
  for (const f of files) {
    const boundary = '----orbit' + crypto.randomBytes(8).toString('hex');
    const head = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${path.basename(f)}"\r\nContent-Type: application/octet-stream\r\n\r\n`);
    const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
    const size = fs.statSync(f).size;
    const { PassThrough } = await import('node:stream');
    const body = new PassThrough();
    body.write(head);
    fs.createReadStream(f).on('end', () => body.end(tail)).pipe(body, { end: false });
    const r = await request('POST', '/api/v1/upload?from=orbit-send', { stream: body, headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}`, 'Content-Length': head.length + size + tail.length } });
    console.log(path.basename(f), r.status, r.text);
  }
}

const started = Date.now();
await (shortcut ? viaShortcut() : viaSessions());
console.log(`total ${((Date.now() - started) / 1000).toFixed(1)}s`);
for (const f of files) console.log(`sha256 ${await sha256(f)}  ${path.basename(f)}`);
