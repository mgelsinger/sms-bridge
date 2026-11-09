// Loads every protocol schema into one Ajv instance.
// Used by the mock phone to validate what it sends and receives.
import { readdirSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

export const protocolRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

export function loadValidator() {
  const schemaDir = join(protocolRoot, "schema");
  const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
  for (const file of readdirSync(schemaDir).filter((f) => f.endsWith(".schema.json"))) {
    ajv.addSchema(JSON.parse(readFileSync(join(schemaDir, file), "utf8")), file);
  }
  return {
    /** Returns null when valid, otherwise a readable error string. */
    check(name, data) {
      const validate = ajv.getSchema(`${name}.schema.json`);
      if (!validate) throw new Error(`unknown schema ${name}`);
      return validate(data) ? null : ajv.errorsText(validate.errors);
    },
  };
}

export function readFixture(schema, kase) {
  return JSON.parse(readFileSync(join(protocolRoot, "fixtures", schema, `${kase}.json`), "utf8"));
}
