#!/usr/bin/env node
// CLI for the mock phone. Configure with environment variables:
//   MOCK_HOST            bind address (default 127.0.0.1)
//   MOCK_PORT            port (default 8765)
//   MOCK_PAIRING_CODE    fixed 6-digit code (default random)
//   MOCK_TOKEN           pre-authorized 43-char token, skips pairing
//   MOCK_INBOUND_EVERY_S simulate an incoming text every N seconds (default off)
//   MOCK_FAIL_TO         comma-separated numbers whose sends fail
//   MOCK_FIRST_SMS_ID    continue SMS ids from here, as a restarted phone would
//   MOCK_READ_DELAY_MS   answer every authorized request this late, as a dozing phone would
//   MOCK_NO_CALLS        any value: behave like a phone from before calls existed
//   MOCK_RING_EVERY_S    simulate a call every N seconds while the line is idle (default off)
//   MOCK_RING_FOR_S      how long such a call rings before the caller gives up (default 8)
import { startMockPhone } from "./mock.mjs";

const env = process.env;
const phone = await startMockPhone({
  host: env.MOCK_HOST ?? "127.0.0.1",
  port: Number(env.MOCK_PORT ?? 8765),
  ...(env.MOCK_PAIRING_CODE ? { pairingCode: env.MOCK_PAIRING_CODE } : {}),
  tokens: env.MOCK_TOKEN ? [env.MOCK_TOKEN] : [],
  failTo: (env.MOCK_FAIL_TO ?? "").split(",").filter(Boolean),
  ...(env.MOCK_FIRST_SMS_ID ? { firstSmsId: Number(env.MOCK_FIRST_SMS_ID) } : {}),
  readDelayMs: Number(env.MOCK_READ_DELAY_MS ?? 0),
  calls: !env.MOCK_NO_CALLS,
});

console.log(`mock phone "${phone.deviceName}" listening on https://${phone.host}:${phone.port}`);
console.log(`fingerprint  ${phone.fingerprint}`);
console.log(`pairing code ${phone.pairingCode} (valid 2 minutes, single use)`);
console.log(`pairing QR   ${JSON.stringify(phone.pairingQr())}`);
console.log(`simulate a text: POST /_mock/inbound {"address":"+15855550101","body":"hi"} with the bearer token`);
console.log(`simulate a call: POST /_mock/ring {"number":"+15855550101"}, then POST /_mock/hangup`);

const every = Number(env.MOCK_INBOUND_EVERY_S ?? 0);
if (every > 0) {
  let n = 0;
  setInterval(() => phone.injectInbound({ address: "+15855550101", body: `Simulated text #${++n}` }), every * 1000);
}

const ringEvery = Number(env.MOCK_RING_EVERY_S ?? 0);
if (ringEvery > 0) {
  setInterval(() => {
    try {
      phone.ring({ number: "+15855550101" });
      // The caller gives up only if nobody answered or declined in the meantime.
      setTimeout(() => {
        if (phone.lineState() === "ringing") phone.remoteHangup();
      }, Number(env.MOCK_RING_FOR_S ?? 8) * 1000);
    } catch {
      // The line is busy; skip this round.
    }
  }, ringEvery * 1000);
}

const stop = async () => {
  await phone.close();
  process.exit(0);
};
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
