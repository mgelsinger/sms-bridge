import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { request } from "node:https";
import { WebSocket } from "ws";
import { startMockPhone } from "../src/mock.mjs";
import { loadValidator } from "../../../protocol/scripts/validator.mjs";

const validator = loadValidator();
let phone;
let token;

// Trust exactly the pinned certificate: the CA is the cert itself, and the
// fingerprint must match too. No verification is skipped.
const tlsFor = (pin) => ({
  ca: phone.certPem,
  checkServerIdentity: (_host, cert) => {
    const fp = cert.fingerprint256.replaceAll(":", "").toLowerCase();
    return fp === pin ? undefined : new Error(`fingerprint mismatch: ${fp}`);
  },
});

function call(method, path, { body, auth = token, pin } = {}) {
  return new Promise((resolve, reject) => {
    const req = request(
      {
        host: phone.host,
        port: phone.port,
        method,
        path,
        headers: { ...(auth ? { authorization: `Bearer ${auth}` } : {}), ...(body ? { "content-type": "application/json" } : {}) },
        // A fresh connection per call. The shared agent reuses sockets and TLS sessions, and
        // Node skips checkServerIdentity on a reused one, so a wrong pin would go unnoticed.
        agent: false,
        ...tlsFor(pin ?? phone.fingerprint),
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const raw = Buffer.concat(chunks);
          const isJson = (res.headers["content-type"] ?? "").startsWith("application/json");
          resolve({ status: res.statusCode, headers: res.headers, json: isJson ? JSON.parse(raw.toString("utf8")) : null, raw });
        });
      },
    );
    req.on("error", reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

function connect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`wss://${phone.host}:${phone.port}/ws`, { headers: { authorization: `Bearer ${token}` }, ...tlsFor(phone.fingerprint) });
    const frames = [];
    const waiters = [];
    ws.on("message", (data) => {
      const frame = JSON.parse(data.toString("utf8"));
      const schema = `ws-${frame.type.replaceAll("_", "-")}`;
      assert.equal(validator.check(schema, frame), null, `frame ${frame.type} must match ${schema}`);
      frames.push(frame);
      for (const w of [...waiters]) if (w.match(frame)) {
        waiters.splice(waiters.indexOf(w), 1);
        w.resolve(frame);
      }
    });
    const next = (match, ms = 3000) => {
      const found = frames.find(match);
      if (found) {
        frames.splice(frames.indexOf(found), 1);
        return Promise.resolve(found);
      }
      return new Promise((res, rej) => {
        const w = { match, resolve: (f) => { clearTimeout(t); frames.splice(frames.indexOf(f), 1); res(f); } };
        const t = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); rej(new Error("timed out waiting for frame")); }, ms);
        waiters.push(w);
      });
    };
    ws.on("open", () => resolve({ ws, next, frames, send: (f) => ws.send(JSON.stringify({ protocol_version: 1, ...f })) }));
    ws.on("unexpected-response", (_req, res) => reject(new Error(`upgrade refused: ${res.statusCode}`)));
    ws.on("error", reject);
  });
}

before(async () => {
  phone = await startMockPhone({ port: 0, strict: true, sendDelayMs: 20, deliveryDelayMs: 40, failTo: ["+15855550666"], log: () => {} });
  token = phone.issueToken();
});
after(async () => phone.close());

test("GET /threads returns the fixture threads, newest first, matching the schema", async () => {
  const res = await call("GET", "/threads");
  assert.equal(res.status, 200);
  assert.equal(validator.check("threads-page", res.json), null);
  assert.deepEqual(res.json.threads.map((t) => t.id), [11, 13, 14, 12]);
  assert.equal(res.json.next_cursor, null);
});

test("thread paging hands back an opaque cursor that reaches every thread", async () => {
  const first = await call("GET", "/threads?limit=3");
  assert.equal(first.json.threads.length, 3);
  const second = await call("GET", `/threads?limit=3&cursor=${encodeURIComponent(first.json.next_cursor)}`);
  assert.deepEqual(second.json.threads.map((t) => t.id), [12]);
  assert.equal(second.json.next_cursor, null);
  const bad = await call("GET", "/threads?cursor=nonsense");
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.code, "bad_cursor");
});

test("requests without a valid token are refused", async () => {
  for (const auth of [null, "A".repeat(43)]) {
    const res = await call("GET", "/threads", { auth });
    assert.equal(res.status, 401);
    assert.equal(res.json.error.code, "unauthorized");
  }
});

test("a client pinned to a different fingerprint refuses the connection", async () => {
  await assert.rejects(call("GET", "/status", { pin: "0".repeat(64) }), /fingerprint mismatch/);
});

test("thread messages, contacts, status, and MMS parts are served", async () => {
  const msgs = await call("GET", "/threads/13/messages");
  assert.equal(validator.check("messages-page", msgs.json), null);
  assert.equal(msgs.json.messages[0].kind, "mms");
  const part = msgs.json.messages.flatMap((m) => m.parts)[0];
  const bytes = await call("GET", `/mms/${part.part_id}`);
  assert.equal(bytes.status, 200);
  assert.equal(bytes.headers["content-type"], part.content_type);
  assert.equal(bytes.raw.length, part.size);
  assert.equal(validator.check("contacts", (await call("GET", "/contacts")).json), null);
  assert.equal(validator.check("status", (await call("GET", "/status")).json), null);
  assert.equal((await call("GET", "/threads/999/messages")).status, 404);
});

test("pairing: wrong code is refused, right code issues a working token once", async () => {
  const p = await startMockPhone({ port: 0, strict: true, pairingCode: "123456", log: () => {} });
  try {
    const pair = (code) =>
      new Promise((resolve, reject) => {
        const req = request(
          { host: p.host, port: p.port, method: "POST", path: "/pair", headers: { "content-type": "application/json" }, ca: p.certPem },
          (res) => {
            let s = "";
            res.on("data", (c) => (s += c));
            res.on("end", () => resolve({ status: res.statusCode, json: JSON.parse(s) }));
          },
        );
        req.on("error", reject);
        req.end(JSON.stringify({ protocol_version: 1, pairing_code: code, client_name: "TEST-PC" }));
      });
    assert.equal((await pair("000000")).json.error.code, "pairing_invalid");
    const ok = await pair("123456");
    assert.equal(ok.status, 200);
    assert.equal(validator.check("pair-response", ok.json), null);
    assert.equal((await pair("123456")).status, 403, "a pairing code works once");
  } finally {
    await p.close();
  }
});

test("pairing locks after 5 failed attempts", async () => {
  const p = await startMockPhone({ port: 0, strict: true, pairingCode: "123456", log: () => {} });
  try {
    const pair = (code) =>
      new Promise((resolve, reject) => {
        const req = request({ host: p.host, port: p.port, method: "POST", path: "/pair", ca: p.certPem }, (res) => {
          res.resume();
          res.on("end", () => resolve(res.statusCode));
        });
        req.on("error", reject);
        req.end(JSON.stringify({ protocol_version: 1, pairing_code: code, client_name: "TEST-PC" }));
      });
    for (let i = 0; i < 5; i++) assert.equal(await pair("999999"), 403);
    assert.equal(await pair("123456"), 429, "even the right code is refused while locked");
  } finally {
    await p.close();
  }
});

test("WebSocket: hello, ping/pong, and refused upgrade without a token", async () => {
  const c = await connect();
  const hello = await c.next((f) => f.type === "hello");
  assert.match(hello.sync_cursor, /^s\d+\.m\d+$/);
  c.send({ type: "ping" });
  await c.next((f) => f.type === "pong");
  c.ws.close();
  const saved = token;
  token = "B".repeat(43);
  await assert.rejects(connect(), /401/);
  token = saved;
});

test("send to a thread: queued, echoed message with client_id, sent, delivered", async () => {
  const c = await connect();
  await c.next((f) => f.type === "hello");
  const clientId = "0b8a6c2e-1d3f-4a5b-8c7d-9e0f1a2b3c4d";
  c.send({ type: "send", client_id: clientId, thread_id: 11, body: "On my way" });
  assert.equal((await c.next((f) => f.type === "send_result" && f.status === "queued")).message_id, null);
  const echo = await c.next((f) => f.type === "message");
  assert.equal(echo.message.client_id, clientId);
  assert.equal(echo.message.direction, "out");
  const sent = await c.next((f) => f.type === "send_result" && f.status === "sent");
  assert.equal(sent.message_id, echo.message.id);
  await c.next((f) => f.type === "send_result" && f.status === "delivered");
  c.ws.close();
});

test("a replayed send with a known client_id is not sent twice", async () => {
  const c = await connect();
  await c.next((f) => f.type === "hello");
  const before = (await call("GET", "/threads/12/messages")).json.messages.length;
  const clientId = "5c1e2d3f-4a5b-4c6d-9e7f-8a9b0c1d2e3f";
  c.send({ type: "send", client_id: clientId, to: ["+15855550102"], body: "Once only" });
  await c.next((f) => f.type === "send_result" && f.status === "delivered");
  c.frames.length = 0;
  c.send({ type: "send", client_id: clientId, to: ["+15855550102"], body: "Once only" });
  const replay = await c.next((f) => f.type === "send_result");
  assert.equal(replay.status, "delivered");
  await new Promise((r) => setTimeout(r, 150));
  assert.equal(c.frames.filter((f) => f.type === "message").length, 0, "no second message frame");
  const afterCount = (await call("GET", "/threads/12/messages")).json.messages.length;
  assert.equal(afterCount, before + 1);
  c.ws.close();
});

test("send failures and bad frames come back as results and errors", async () => {
  const c = await connect();
  await c.next((f) => f.type === "hello");
  c.send({ type: "send", client_id: "7d2e3f4a-5b6c-4d7e-8f9a-0b1c2d3e4f5a", to: ["+15855550666"], body: "nope" });
  const failed = await c.next((f) => f.type === "send_result" && f.status === "failed");
  assert.ok(failed.error);
  c.send({ type: "send", client_id: "not-a-uuid", thread_id: 11, body: "x" });
  assert.equal((await c.next((f) => f.type === "error")).code, "bad_request");
  c.send({ type: "mark_read", thread_id: 11 });
  assert.equal((await c.next((f) => f.type === "error")).code, "not_default_sms_app");
  c.ws.close();
});

test("incremental sync picks up an inbound text, and it is pushed live", async () => {
  const start = await call("GET", "/messages");
  assert.deepEqual(start.json.messages, []);
  const c = await connect();
  await c.next((f) => f.type === "hello");
  const injected = phone.injectInbound({ address: "+15855550101", body: "Pushed live" });
  const live = await c.next((f) => f.type === "message");
  assert.equal(live.message.id, injected.id);
  const sync = await call("GET", `/messages?cursor=${encodeURIComponent(start.json.next_cursor)}`);
  assert.equal(validator.check("sync-page", sync.json), null);
  assert.deepEqual(sync.json.messages.map((m) => m.id), [injected.id]);
  const again = await call("GET", `/messages?cursor=${encodeURIComponent(sync.json.next_cursor)}`);
  assert.deepEqual(again.json.messages, []);
  c.ws.close();
});

test("a frame with the wrong protocol version closes the socket with 4000", async () => {
  const c = await connect();
  await c.next((f) => f.type === "hello");
  const closed = new Promise((resolve) => c.ws.on("close", (code) => resolve(code)));
  c.ws.send(JSON.stringify({ protocol_version: 2, type: "ping" }));
  assert.equal(await closed, 4000);
});

// Calls ----------------------------------------------------------------------

test("call history is paged oldest first, and status says what the phone can do", async () => {
  const status = await call("GET", "/status");
  assert.deepEqual(status.json.calls, { history: true, dial: true, answer: true, end: true });
  const first = await call("GET", "/calls?limit=3");
  assert.equal(validator.check("calls-page", first.json), null);
  assert.deepEqual(first.json.calls.map((c) => c.id), ["call:412", "call:413", "call:414"]);
  assert.equal(first.json.has_more, true);
  const rest = await call("GET", `/calls?cursor=${encodeURIComponent(first.json.next_cursor)}`);
  assert.deepEqual(rest.json.calls.map((c) => c.id), ["call:415"]);
  assert.equal(rest.json.has_more, false);
  const none = await call("GET", `/calls?cursor=${encodeURIComponent(rest.json.next_cursor)}`);
  assert.deepEqual(none.json.calls, []);
  assert.equal(none.json.next_cursor, rest.json.next_cursor, "the cursor stays put when nothing is new");
  assert.equal((await call("GET", "/calls?cursor=nonsense")).json.error.code, "bad_cursor");
});

test("a call rings, is answered from the client, ended, and lands in the history", async () => {
  const c = await connect();
  await c.next((f) => f.type === "hello");
  assert.equal((await c.next((f) => f.type === "call_state")).state, "idle", "the line is reported right after hello");
  phone.ring({ number: "+15855550101" });
  const ringing = await c.next((f) => f.type === "call_state");
  assert.deepEqual([ringing.state, ringing.direction, ringing.number], ["ringing", "in", "+15855550101"]);
  c.send({ type: "call_action", client_id: "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", action: "answer" });
  assert.equal((await c.next((f) => f.type === "call_result")).status, "ok");
  assert.equal((await c.next((f) => f.type === "call_state")).state, "active");
  c.send({ type: "call_action", client_id: "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e", action: "end" });
  assert.equal((await c.next((f) => f.type === "call_result")).status, "ok");
  const idle = await c.next((f) => f.type === "call_state");
  assert.deepEqual([idle.state, idle.direction, idle.number], ["idle", null, null]);
  const logged = await c.next((f) => f.type === "call");
  assert.equal(logged.call.kind, "in");
  assert.equal(logged.call.number, "+15855550101");
  const page = await call("GET", "/calls?cursor=c415");
  assert.ok(page.json.calls.some((entry) => entry.id === logged.call.id), "the history has the entry the frame announced");
  c.ws.close();
});

test("a call nobody answers is logged as missed, a declined one as rejected", async () => {
  const c = await connect();
  await c.next((f) => f.type === "call_state" && f.state === "idle");
  phone.ring({ number: null });
  const ringing = await c.next((f) => f.type === "call_state" && f.state === "ringing");
  assert.equal(ringing.number, null, "a withheld number stays withheld");
  phone.remoteHangup();
  assert.equal((await c.next((f) => f.type === "call")).call.kind, "missed");
  await c.next((f) => f.type === "call_state" && f.state === "idle");

  phone.ring({ number: "+15855550102" });
  await c.next((f) => f.type === "call_state" && f.state === "ringing");
  c.send({ type: "call_action", client_id: "3c4d5e6f-7a8b-4c9d-8e1f-2a3b4c5d6e7f", action: "end" });
  assert.equal((await c.next((f) => f.type === "call_result")).status, "ok");
  const declined = await c.next((f) => f.type === "call");
  assert.deepEqual([declined.call.kind, declined.call.duration_s], ["rejected", 0]);
  c.ws.close();
});

test("dial places one call; a repeated dial, an emergency number and a busy line do not", async () => {
  const c = await connect();
  await c.next((f) => f.type === "call_state" && f.state === "idle");
  const dialId = "4d5e6f7a-8b9c-4d0e-9f2a-3b4c5d6e7f8a";
  c.send({ type: "dial", client_id: dialId, to: "+15855550177" });
  assert.equal((await c.next((f) => f.type === "call_result" && f.client_id === dialId)).status, "ok");
  const active = await c.next((f) => f.type === "call_state");
  assert.deepEqual([active.state, active.direction, active.number], ["active", "out", "+15855550177"]);

  // The same request again: answered as before, and no second call.
  c.send({ type: "dial", client_id: dialId, to: "+15855550177" });
  assert.equal((await c.next((f) => f.type === "call_result" && f.client_id === dialId)).status, "ok");
  // A different request while the line is busy.
  c.send({ type: "dial", client_id: "5e6f7a8b-9c0d-4e1f-a03b-4c5d6e7f8a9b", to: "+15855550102" });
  const busy = await c.next((f) => f.type === "call_result");
  assert.deepEqual([busy.status, busy.error], ["failed", "a call is already in progress"]);

  c.send({ type: "call_action", client_id: "6f7a8b9c-0d1e-4f2a-b14c-5d6e7f8a9b0c", action: "end" });
  assert.equal((await c.next((f) => f.type === "call_result")).status, "ok");
  const logged = await c.next((f) => f.type === "call");
  assert.deepEqual([logged.call.kind, logged.call.number], ["out", "+15855550177"]);
  await c.next((f) => f.type === "call_state" && f.state === "idle");
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(c.frames.filter((f) => f.type === "call").length, 0, "one call, one history entry");

  c.send({ type: "dial", client_id: "7a8b9c0d-1e2f-4a3b-825d-6e7f8a9b0c1d", to: "911" });
  const refused = await c.next((f) => f.type === "call_result");
  assert.deepEqual([refused.status, refused.error], ["failed", "emergency numbers are dialed on the phone"]);
  c.send({ type: "dial", client_id: "8b9c0d1e-2f3a-4b4c-936e-7f8a9b0c1d2e", to: "call me maybe" });
  assert.equal((await c.next((f) => f.type === "call_result")).status, "failed");
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(c.frames.filter((f) => f.type === "call_state").length, 0, "the line never left idle");
  c.ws.close();
});

test("call actions with nothing to act on fail, and malformed ones are errors", async () => {
  const c = await connect();
  await c.next((f) => f.type === "call_state" && f.state === "idle");
  c.send({ type: "call_action", client_id: "9c0d1e2f-3a4b-4c5d-a47f-8a9b0c1d2e3f", action: "answer" });
  assert.deepEqual(
    (({ status, error }) => [status, error])(await c.next((f) => f.type === "call_result")),
    ["failed", "no call is ringing"],
  );
  c.send({ type: "call_action", client_id: "0d1e2f3a-4b5c-4d6e-b58a-9b0c1d2e3f4a", action: "end" });
  assert.equal((await c.next((f) => f.type === "call_result")).status, "failed");
  c.send({ type: "call_action", client_id: "1e2f3a4b-5c6d-4e7f-869b-0c1d2e3f4a5b", action: "silence" });
  assert.equal((await c.next((f) => f.type === "error")).code, "bad_request");
  c.send({ type: "dial", client_id: "2f3a4b5c-6d7e-4f8a-970c-1d2e3f4a5b6c" });
  assert.equal((await c.next((f) => f.type === "error")).code, "bad_request");
  c.ws.close();
});

test("a phone from before calls says nothing about them", async () => {
  const old = await startMockPhone({ port: 0, strict: true, calls: false, log: () => {} });
  const saved = { phone, token };
  phone = old;
  token = old.issueToken();
  try {
    assert.equal((await call("GET", "/status")).json.calls, undefined);
    assert.equal((await call("GET", "/calls")).status, 404);
    const c = await connect();
    await c.next((f) => f.type === "hello");
    c.send({ type: "dial", client_id: "3a4b5c6d-7e8f-4a9b-a81d-2e3f4a5b6c7d", to: "+15855550177" });
    c.send({ type: "ping" });
    await c.next((f) => f.type === "pong");
    assert.equal(c.frames.length, 0, "no call_state after hello and no answer to dial");
    c.ws.close();
  } finally {
    await old.close();
    ({ phone, token } = saved);
  }
});
