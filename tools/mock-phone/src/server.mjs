#!/usr/bin/env node
// CLI for the mock phone. Configure with environment variables:
//   MOCK_HOST            bind address (default 127.0.0.1)
//   MOCK_PORT            port (default 8765)
//   MOCK_PAIRING_CODE    fixed 6-digit code (default random)
//   MOCK_TOKEN           pre-authorized 43-char token, skips pairing
//   MOCK_INBOUND_EVERY_S simulate an incoming text every N seconds (default off)
//   MOCK_FAIL_TO         comma-separated numbers whose sends fail
//   MOCK_FIRST_SMS_ID    continue SMS ids from here, as a restarted phone would
import { startMockPhone } from "./mock.mjs";

const env = process.env;
const phone = await startMockPhone({
  host: env.MOCK_HOST ?? "127.0.0.1",
  port: Number(env.MOCK_PORT ?? 8765),
  ...(env.MOCK_PAIRING_CODE ? { pairingCode: env.MOCK_PAIRING_CODE } : {}),
  tokens: env.MOCK_TOKEN ? [env.MOCK_TOKEN] : [],
  failTo: (env.MOCK_FAIL_TO ?? "").split(",").filter(Boolean),
  ...(env.MOCK_FIRST_SMS_ID ? { firstSmsId: Number(env.MOCK_FIRST_SMS_ID) } : {}),
});

console.log(`mock phone "${phone.deviceName}" listening on https://${phone.host}:${phone.port}`);
console.log(`fingerprint  ${phone.fingerprint}`);
console.log(`pairing code ${phone.pairingCode} (valid 2 minutes, single use)`);
console.log(`pairing QR   ${JSON.stringify(phone.pairingQr())}`);
console.log(`simulate a text: POST /_mock/inbound {"address":"+15855550101","body":"hi"} with the bearer token`);

const every = Number(env.MOCK_INBOUND_EVERY_S ?? 0);
if (every > 0) {
  let n = 0;
  setInterval(() => phone.injectInbound({ address: "+15855550101", body: `Simulated text #${++n}` }), every * 1000);
}

const stop = async () => {
  await phone.close();
  process.exit(0);
};
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
