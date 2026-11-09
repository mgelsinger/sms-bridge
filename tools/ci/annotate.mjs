#!/usr/bin/env node
// Turns a failed build log into GitHub Actions error annotations, which are
// readable through the public checks API without downloading logs.
// Usage: node tools/ci/annotate.mjs <log file> [title]
import { readFileSync, existsSync } from "node:fs";

const [logPath, title = "build failed"] = process.argv.slice(2);
if (!logPath || !existsSync(logPath)) {
  console.log(`::error title=${title}::no log at ${logPath}`);
  process.exit(0);
}
const lines = readFileSync(logPath, "utf8").replace(/\x1b\[[0-9;]*m/g, "").split(/\r?\n/);
const esc = (s) => s.replaceAll("%", "%25").replaceAll("\r", "").replaceAll("\n", "%0A");
const repoRel = (p) => p.replace(/^file:\/\//, "").replace(/^.*?\/sms-bridge\/sms-bridge\//, "").replace(/^[A-Za-z]:\\a\\sms-bridge\\sms-bridge\\/, "").replaceAll("\\", "/");
const out = [];
const emit = (msg, file, line, col) => {
  const loc = file ? `file=${repoRel(file)}${line ? `,line=${line}` : ""}${col ? `,col=${col}` : ""},` : "";
  out.push(`::error ${loc}title=${title}::${esc(msg.slice(0, 4000))}`);
};

for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  // Kotlin compiler: e: file:///path/File.kt:12:5 message
  let m = /^e: (file:\/\/\S+?\.kts?):(\d+):(\d+) (.*)$/.exec(l);
  if (m) { emit(m[4], m[1], m[2], m[3]); continue; }
  // rustc: error[E0425]: message  /  --> path:line:col
  m = /^error(\[E\d+\])?: (.*)$/.exec(l);
  if (m && !/^error: could not compile|^error: aborting/.test(l)) {
    const loc = lines.slice(i + 1, i + 4).map((x) => /-->\s+(.+?):(\d+):(\d+)/.exec(x)).find(Boolean);
    const block = [l];
    for (let j = i + 1; j < Math.min(lines.length, i + 14) && lines[j].trim() !== ""; j++) block.push(lines[j]);
    emit(block.join("\n"), loc ? `desktop/${loc[1]}` : undefined, loc?.[2], loc?.[3]);
    continue;
  }
  // Gradle: * What went wrong: ... * Try:
  if (l.startsWith("* What went wrong:")) {
    const block = [];
    for (let j = i + 1; j < lines.length && !lines[j].startsWith("* Try:"); j++) block.push(lines[j]);
    emit(block.join("\n").trim());
    continue;
  }
  // JUnit / Gradle test failures: "FixtureRoundTripTest > foo FAILED" plus following detail
  if (/ FAILED$/.test(l) && / > /.test(l)) {
    emit([l, ...lines.slice(i + 1, i + 6)].join("\n"));
    continue;
  }
  // TypeScript: src/App.tsx(3,10): error TS2305: ...
  m = /^(.+?\.tsx?)\((\d+),(\d+)\): error (TS\d+: .*)$/.exec(l);
  if (m) { emit(m[4], `desktop/${m[1]}`, m[2], m[3]); continue; }
  // npm / vitest / node --test failures
  if (/^(not ok \d+|\s*FAIL\s|AssertionError|::error)/.test(l)) {
    emit(lines.slice(i, i + 8).join("\n"));
  }
}

if (out.length === 0) emit(`no recognised error; last lines:\n${lines.slice(-40).join("\n")}`);
for (const line of out.slice(0, 40)) console.log(line);
