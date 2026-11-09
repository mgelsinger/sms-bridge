// A fake phone that speaks protocol v1 over TLS, seeded from protocol/fixtures.
// It validates everything it sends and receives against the shared schemas, so
// desktop work against it is work against the real contract.
import { createServer } from "node:https";
import { createHash, randomBytes, randomInt, X509Certificate } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";
import { loadValidator, readFixture, protocolRoot } from "../../../protocol/scripts/validator.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const PV = 1;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export const TEST_CERT_PATH = join(here, "..", "certs", "test-cert.pem");
export const TEST_KEY_PATH = join(here, "..", "certs", "test-key.pem");

export function certFingerprint(pem) {
  // SHA-256 of the DER certificate, lowercase hex, no separators.
  return new X509Certificate(pem).fingerprint256.replaceAll(":", "").toLowerCase();
}

const sha256 = (s) => createHash("sha256").update(s).digest("hex");
const newToken = () => randomBytes(32).toString("base64url");

function loadState() {
  const threads = new Map(readFixture("threads-page", "default").threads.map((t) => [t.id, { ...t }]));
  const messages = new Map();
  const dir = join(protocolRoot, "fixtures", "messages-page");
  for (const file of readdirSync(dir).filter((f) => f.startsWith("thread-") && f.endsWith(".json"))) {
    const page = JSON.parse(readFileSync(join(dir, file), "utf8"));
    messages.set(page.thread_id, [...page.messages].sort((a, b) => a.date - b.date));
  }
  let maxSms = 0;
  let maxMms = 0;
  for (const list of messages.values()) {
    for (const m of list) {
      const [kind, n] = m.id.split(":");
      if (kind === "sms") maxSms = Math.max(maxSms, Number(n));
      else maxMms = Math.max(maxMms, Number(n));
    }
  }
  return {
    threads,
    messages,
    contacts: readFixture("contacts", "default").contacts,
    status: (({ protocol_version, ...s }) => s)(readFixture("status", "default")),
    maxSms,
    maxMms,
    nextPartId: 9000,
  };
}

function idNumber(id) {
  const [kind, n] = id.split(":");
  return { kind, n: Number(n) };
}

export async function startMockPhone(options = {}) {
  const opts = {
    host: "127.0.0.1",
    port: 8765,
    deviceName: "Mock Pixel",
    pairingCode: String(randomInt(0, 1_000_000)).padStart(6, "0"),
    pairingTtlMs: 120_000,
    autoAcceptPairing: true,
    tokens: [],
    sendDelayMs: 250,
    deliveryDelayMs: 800,
    failTo: [],
    strict: false,
    log: (line) => console.log(line),
    ...options,
  };
  const certPem = readFileSync(opts.certPath ?? TEST_CERT_PATH, "utf8");
  const keyPem = readFileSync(opts.keyPath ?? TEST_KEY_PATH, "utf8");
  const fingerprint = certFingerprint(certPem);
  const validator = loadValidator();
  const state = loadState();
  // A restarted real phone never reuses row ids; let tests continue the sequence.
  if (opts.firstSmsId) state.maxSms = Math.max(state.maxSms, opts.firstSmsId - 1);
  const startedAt = Date.now();
  const tokenHashes = new Set(opts.tokens.map(sha256));
  const pairing = { code: opts.pairingCode, expiresAt: Date.now() + opts.pairingTtlMs, used: false, failures: [], lockedUntil: 0 };
  const sends = new Map(); // client_id -> { result, at }
  const sockets = new Set();

  const outgoing = (schema, body) => {
    const problem = validator.check(schema, body);
    if (problem) {
      const msg = `mock emitted invalid ${schema}: ${problem}`;
      if (opts.strict) throw new Error(msg);
      opts.log(`WARN ${msg}`);
    }
    return body;
  };

  const sendJson = (res, code, schema, body) => {
    const payload = JSON.stringify(outgoing(schema, { protocol_version: PV, ...body }));
    res.writeHead(code, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
    res.end(payload);
  };
  const sendError = (res, code, errCode, message) => sendJson(res, code, "error", { error: { code: errCode, message } });

  const authorized = (req) => {
    const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? "");
    return Boolean(m && tokenHashes.has(sha256(m[1])));
  };

  const readBody = (req) =>
    new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on("data", (c) => {
        size += c.length;
        if (size > 64 * 1024) {
          reject(new Error("body too large"));
          req.destroy();
        } else chunks.push(c);
      });
      req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
      req.on("error", reject);
    });

  const statusBody = () => ({
    ...state.status,
    device_name: opts.deviceName,
    uptime_s: Math.floor((Date.now() - startedAt) / 1000),
    paired_clients: tokenHashes.size,
  });

  const syncCursor = () => `s${state.maxSms}.m${state.maxMms}`;
  const parseSyncCursor = (c) => {
    const m = /^s(\d+)\.m(\d+)$/.exec(c);
    return m ? { sms: Number(m[1]), mms: Number(m[2]) } : null;
  };
  const parseOffset = (c, prefix) => {
    if (c === undefined || c === null) return 0;
    const m = new RegExp(`^${prefix}:(\\d+)$`).exec(c);
    return m ? Number(m[1]) : null;
  };
  const parseLimit = (raw, def, max) => {
    if (raw === null) return def;
    const n = Number(raw);
    return Number.isInteger(n) && n >= 1 && n <= max ? n : null;
  };

  const allMessagesOldestFirst = () =>
    [...state.messages.values()].flat().sort((a, b) => a.date - b.date || a.id.localeCompare(b.id));

  function broadcast(schema, frame) {
    const text = JSON.stringify(outgoing(schema, { protocol_version: PV, ...frame }));
    for (const ws of sockets) if (ws.readyState === ws.OPEN) ws.send(text);
  }

  function addMessage({ threadId, direction, address, body, status, clientId, read }) {
    state.maxSms += 1;
    const thread = state.threads.get(threadId);
    const message = {
      id: `sms:${state.maxSms}`,
      thread_id: threadId,
      kind: "sms",
      direction,
      address,
      participants: thread ? [...thread.participants] : [address],
      body,
      parts: [],
      date: Date.now(),
      read,
      status,
      client_id: clientId ?? null,
    };
    if (!state.messages.has(threadId)) state.messages.set(threadId, []);
    state.messages.get(threadId).push(message);
    thread.snippet = body.slice(0, 200);
    thread.date = message.date;
    thread.message_count += 1;
    if (!read) thread.unread_count += 1;
    return message;
  }

  function threadFor(address) {
    for (const t of state.threads.values()) {
      if (t.participants.length === 1 && t.participants[0] === address) return t.id;
    }
    const id = Math.max(0, ...state.threads.keys()) + 1;
    state.threads.set(id, { id, participants: [address], snippet: null, date: Date.now(), unread_count: 0, message_count: 0 });
    return id;
  }

  /** Simulates a text arriving on the phone. */
  function injectInbound({ address, body }) {
    const message = addMessage({ threadId: threadFor(address), direction: "in", address, body, status: null, read: false });
    opts.log(`inbound sms ${message.id} from ${address}`);
    broadcast("ws-message", { type: "message", message });
    return message;
  }

  async function handleRequest(req, res) {
    const url = new URL(req.url, "https://phone");
    const path = url.pathname;
    res.on("finish", () => opts.log(`${req.method} ${path} ${res.statusCode}`));
    try {
      if (req.method === "POST" && path === "/pair") return await handlePair(req, res);
      if (!authorized(req)) return sendError(res, 401, "unauthorized", "missing or unknown token");

      if (req.method === "GET" && path === "/status") return sendJson(res, 200, "status", statusBody());

      if (req.method === "GET" && path === "/threads") {
        const limit = parseLimit(url.searchParams.get("limit"), 50, 200);
        const offset = parseOffset(url.searchParams.get("cursor"), "t");
        if (limit === null) return sendError(res, 400, "bad_request", "limit must be 1-200");
        if (offset === null) return sendError(res, 400, "bad_cursor", "unknown cursor");
        const all = [...state.threads.values()].filter((t) => t.message_count > 0).sort((a, b) => b.date - a.date);
        const page = all.slice(offset, offset + limit);
        const next = offset + limit < all.length ? `t:${offset + limit}` : null;
        return sendJson(res, 200, "threads-page", { threads: page, next_cursor: next });
      }

      const threadMatch = /^\/threads\/(\d+)\/messages$/.exec(path);
      if (req.method === "GET" && threadMatch) {
        const threadId = Number(threadMatch[1]);
        if (!state.threads.has(threadId)) return sendError(res, 404, "not_found", "no such thread");
        const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
        const offset = parseOffset(url.searchParams.get("cursor"), "o");
        if (limit === null) return sendError(res, 400, "bad_request", "limit must be 1-500");
        if (offset === null) return sendError(res, 400, "bad_cursor", "unknown cursor");
        const newestFirst = [...(state.messages.get(threadId) ?? [])].reverse();
        const page = newestFirst.slice(offset, offset + limit);
        const next = offset + limit < newestFirst.length ? `o:${offset + limit}` : null;
        return sendJson(res, 200, "messages-page", { thread_id: threadId, messages: page, next_cursor: next });
      }

      if (req.method === "GET" && path === "/messages") {
        const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
        if (limit === null) return sendError(res, 400, "bad_request", "limit must be 1-500");
        const raw = url.searchParams.get("cursor");
        if (raw === null) return sendJson(res, 200, "sync-page", { messages: [], next_cursor: syncCursor(), has_more: false });
        const from = parseSyncCursor(raw);
        if (!from) return sendError(res, 400, "bad_cursor", "unknown cursor");
        const newer = allMessagesOldestFirst().filter((m) => {
          const { kind, n } = idNumber(m.id);
          return kind === "sms" ? n > from.sms : n > from.mms;
        });
        const page = newer.slice(0, limit);
        let sms = from.sms;
        let mms = from.mms;
        for (const m of page) {
          const { kind, n } = idNumber(m.id);
          if (kind === "sms") sms = Math.max(sms, n);
          else mms = Math.max(mms, n);
        }
        return sendJson(res, 200, "sync-page", { messages: page, next_cursor: `s${sms}.m${mms}`, has_more: newer.length > limit });
      }

      if (req.method === "GET" && path === "/contacts") return sendJson(res, 200, "contacts", { contacts: state.contacts });

      const partMatch = /^\/mms\/(\d+)$/.exec(path);
      if (req.method === "GET" && partMatch) {
        const partId = Number(partMatch[1]);
        const part = allMessagesOldestFirst().flatMap((m) => m.parts).find((p) => p.part_id === partId);
        if (!part) return sendError(res, 404, "not_found", "no such part");
        let bytes;
        try {
          bytes = readFileSync(join(here, "..", "assets", `part-${partId}.png`));
        } catch {
          return sendError(res, 404, "not_found", "part has no sample bytes in the mock");
        }
        res.writeHead(200, { "content-type": part.content_type, "content-length": bytes.length });
        return res.end(bytes);
      }

      // Mock-only hook so a developer can simulate an incoming text.
      if (req.method === "POST" && path === "/_mock/inbound") {
        const body = JSON.parse(await readBody(req));
        if (typeof body.address !== "string" || typeof body.body !== "string" || !body.body) {
          return sendError(res, 400, "bad_request", "need address and body");
        }
        const message = injectInbound(body);
        res.writeHead(200, { "content-type": "application/json" });
        return res.end(JSON.stringify({ id: message.id }));
      }

      return sendError(res, 404, "not_found", "no such endpoint");
    } catch (err) {
      opts.log(`ERROR ${req.method} ${path}: ${err.message}`);
      if (!res.headersSent) sendError(res, 500, "internal", "mock error");
    }
  }

  async function handlePair(req, res) {
    const now = Date.now();
    if (now < pairing.lockedUntil) return sendError(res, 429, "rate_limited", "too many attempts");
    let body;
    try {
      body = JSON.parse(await readBody(req));
    } catch {
      return sendError(res, 400, "bad_request", "body must be JSON");
    }
    const problem = validator.check("pair-request", body);
    if (problem) return sendError(res, 400, "bad_request", problem);
    const valid = !pairing.used && now < pairing.expiresAt && body.pairing_code === pairing.code;
    if (!valid) {
      pairing.failures = pairing.failures.filter((t) => now - t < 600_000);
      pairing.failures.push(now);
      if (pairing.failures.length >= 5) pairing.lockedUntil = now + 600_000;
      return sendError(res, 403, "pairing_invalid", "wrong or expired pairing code");
    }
    const accept = opts.autoAcceptPairing ? true : await opts.confirmPairing?.(body.client_name);
    if (!accept) return sendError(res, 403, "pairing_rejected", "pairing was declined on the phone");
    pairing.used = true;
    const token = newToken();
    tokenHashes.add(sha256(token));
    opts.log(`paired client "${body.client_name}"`);
    return sendJson(res, 200, "pair-response", { token, device_name: opts.deviceName });
  }

  function handleFrame(ws, text) {
    let frame;
    const reply = (schema, body) => ws.send(JSON.stringify(outgoing(schema, { protocol_version: PV, ...body })));
    const fail = (code, message, clientId = null) => reply("ws-error", { type: "error", code, message, client_id: clientId });
    try {
      frame = JSON.parse(text);
    } catch {
      return fail("bad_request", "frame is not JSON");
    }
    if (frame?.protocol_version !== PV) {
      fail("unsupported_version", "this phone speaks protocol version 1");
      return ws.close(4000, "unsupported protocol version");
    }
    const schema = { send: "ws-send", ping: "ws-ping", mark_read: "ws-mark-read" }[frame.type];
    if (!schema) return; // Unknown client frame types are ignored for forward compatibility.
    const problem = validator.check(schema, frame);
    if (problem) {
      // Echo client_id only when it is well formed; a malformed one cannot be correlated anyway.
      const echo = UUID_V4.test(frame.client_id ?? "") ? frame.client_id : null;
      return fail("bad_request", problem, echo);
    }

    if (frame.type === "ping") return reply("ws-pong", { type: "pong" });
    if (frame.type === "mark_read") {
      if (!state.status.is_default_sms_app) return fail("not_default_sms_app", "Only the default SMS app can mark messages read");
      const thread = state.threads.get(frame.thread_id);
      if (!thread) return fail("not_found", "no such thread");
      thread.unread_count = 0;
      for (const m of state.messages.get(frame.thread_id) ?? []) m.read = true;
      return broadcast("ws-thread-updated", { type: "thread_updated", thread_id: frame.thread_id });
    }
    return handleSend(ws, frame);
  }

  function handleSend(ws, frame) {
    const result = (status, messageId, error = null) => {
      const body = { type: "send_result", client_id: frame.client_id, message_id: messageId, status, error };
      sends.set(frame.client_id, { result: body, at: Date.now() });
      broadcast("ws-send-result", body);
    };
    const known = sends.get(frame.client_id);
    if (known && Date.now() - known.at < 86_400_000) {
      ws.send(JSON.stringify(outgoing("ws-send-result", { protocol_version: PV, ...known.result })));
      return;
    }
    let threadId = frame.thread_id;
    let address;
    if (threadId !== undefined) {
      const thread = state.threads.get(threadId);
      if (!thread) return result("failed", null, "no such thread");
      if (thread.participants.length !== 1) return result("failed", null, "group threads need MMS, not supported in version 1");
      address = thread.participants[0];
    } else {
      address = frame.to[0];
      threadId = threadFor(address);
    }
    result("queued", null);
    setTimeout(() => {
      if (opts.failTo.includes(address)) return result("failed", null, "RESULT_ERROR_GENERIC_FAILURE");
      const message = addMessage({ threadId, direction: "out", address, body: frame.body, status: "sent", clientId: frame.client_id, read: true });
      broadcast("ws-message", { type: "message", message });
      result("sent", message.id);
      setTimeout(() => {
        message.status = "delivered";
        result("delivered", message.id);
      }, opts.deliveryDelayMs);
    }, opts.sendDelayMs);
  }

  const server = createServer({ key: keyPem, cert: certPem, minVersion: "TLSv1.2" }, handleRequest);
  const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
  server.on("upgrade", (req, socket, head) => {
    const path = new URL(req.url, "https://phone").pathname;
    if (path !== "/ws" || !authorized(req)) {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      socket.destroy();
      opts.log(`WS upgrade ${path} 401`);
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      sockets.add(ws);
      opts.log("WS connected");
      ws.on("message", (data, isBinary) => {
        if (isBinary) return;
        try {
          handleFrame(ws, data.toString("utf8"));
        } catch (err) {
          opts.log(`ERROR ws frame: ${err.message}`);
        }
      });
      ws.on("close", () => {
        sockets.delete(ws);
        opts.log("WS closed");
      });
      ws.send(
        JSON.stringify(
          outgoing("ws-hello", {
            protocol_version: PV,
            type: "hello",
            device_name: opts.deviceName,
            sync_cursor: syncCursor(),
            is_default_sms_app: state.status.is_default_sms_app,
          }),
        ),
      );
    });
  });

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(opts.port, opts.host, resolve);
  });
  const port = server.address().port;

  return {
    host: opts.host,
    port,
    fingerprint,
    pairingCode: pairing.code,
    certPem,
    deviceName: opts.deviceName,
    injectInbound,
    /** Issues a token as if pairing had happened, for tests and scripted dev. */
    issueToken() {
      const token = newToken();
      tokenHashes.add(sha256(token));
      return token;
    },
    revokeAll() {
      tokenHashes.clear();
      for (const ws of sockets) ws.close(4001, "token revoked");
    },
    pairingQr() {
      return outgoing("pairing-qr", {
        protocol_version: PV,
        host: opts.host,
        port,
        fingerprint,
        pairing_code: pairing.code,
        device_name: opts.deviceName,
      });
    },
    async close() {
      for (const ws of sockets) ws.terminate();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
