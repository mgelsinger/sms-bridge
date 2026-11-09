#!/usr/bin/env node
// Posts JUnit XML results as annotations: one notice per suite with counts,
// one error per failed test. Usage: node tools/ci/junit-summary.mjs <dir> [label]
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join } from "node:path";

const [dir, label = "tests"] = process.argv.slice(2);
const files = [];
const walk = (d) => {
  if (!existsSync(d)) return;
  for (const f of readdirSync(d)) {
    const p = join(d, f);
    if (statSync(p).isDirectory()) walk(p);
    else if (f.endsWith(".xml")) files.push(p);
  }
};
walk(dir);
const esc = (s) => s.replaceAll("%", "%25").replaceAll("\r", "").replaceAll("\n", "%0A");
const unxml = (s) => s.replaceAll("&quot;", '"').replaceAll("&apos;", "'").replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&");
let total = 0;
let failed = 0;
for (const file of files) {
  const xml = readFileSync(file, "utf8");
  for (const m of xml.matchAll(/<testsuite\b([^>]*)>/g)) {
    const attr = (n) => Number((new RegExp(`\\b${n}="(\\d+)"`).exec(m[1]) ?? [])[1] ?? 0);
    const name = (/\bname="([^"]*)"/.exec(m[1]) ?? [])[1] ?? file;
    const t = attr("tests");
    const f = attr("failures") + attr("errors");
    total += t;
    failed += f;
    console.log(`::notice title=${label}::${name.split(".").pop()}: ${t - f - attr("skipped")} passed, ${f} failed, ${attr("skipped")} skipped`);
  }
  for (const m of xml.matchAll(/<testcase\b[^>]*\bname="([^"]*)"[^>]*\bclassname="([^"]*)"[^>]*>\s*<(failure|error)\b[^>]*?(?:message="([^"]*)")?[^>]*>([\s\S]*?)<\/\3>/g)) {
    const detail = unxml(m[5]).split("\n").slice(0, 12).join("\n");
    console.log(`::error title=${label} failed::${esc(`${m[2].split(".").pop()} > ${unxml(m[1])}\n${unxml(m[4] ?? "")}\n${detail}`.slice(0, 4000))}`);
  }
}
console.log(`::notice title=${label} total::${total} tests, ${failed} failed, ${files.length} report file(s)`);
