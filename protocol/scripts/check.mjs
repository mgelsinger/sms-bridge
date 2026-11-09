// Validates every fixture against its schema.
// fixtures/<schema>/*.json must pass; fixtures/<schema>/invalid/*.json must fail.
// Every schema except common must have at least one passing fixture.
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const schemaDir = join(root, "schema");
const fixtureDir = join(root, "fixtures");

const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
const names = readdirSync(schemaDir)
  .filter((f) => f.endsWith(".schema.json"))
  .map((f) => f.replace(".schema.json", ""));
for (const name of names) {
  ajv.addSchema(JSON.parse(readFileSync(join(schemaDir, `${name}.schema.json`), "utf8")), `${name}.schema.json`);
}

const problems = [];
let passed = 0;
const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));
const jsonFiles = (dir) => (existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith(".json")) : []);

for (const name of names) {
  if (name === "common") continue;
  const validate = ajv.getSchema(`${name}.schema.json`);
  const dir = join(fixtureDir, name);
  const valid = jsonFiles(dir);
  if (valid.length === 0) problems.push(`${name}: no fixtures (every protocol message needs one)`);
  for (const file of valid) {
    if (validate(readJson(join(dir, file)))) passed++;
    else problems.push(`${name}/${file}: ${ajv.errorsText(validate.errors)}`);
  }
  for (const file of jsonFiles(join(dir, "invalid"))) {
    if (validate(readJson(join(dir, "invalid", file)))) problems.push(`${name}/invalid/${file}: passed but must fail`);
    else passed++;
  }
}
for (const dir of readdirSync(fixtureDir)) {
  if (!names.includes(dir)) problems.push(`fixtures/${dir}: no schema named ${dir}.schema.json`);
}

if (problems.length) {
  for (const p of problems) console.error(`::error title=protocol check::${p}`);
  console.error(`${problems.length} problem(s), ${passed} check(s) passed`);
  process.exit(1);
}
console.log(`protocol ok: ${passed} fixture checks across ${names.length - 1} schemas`);
