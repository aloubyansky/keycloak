#!/usr/bin/env node

/**
 * Generates a compact JSON manifest of production npm dependencies
 * for workspace packages that ship in the Keycloak distribution.
 *
 * Usage: node generate-npm-manifest.mjs [--output <path>]
 *
 * Output format:
 * {
 *   "packages": [
 *     { "name": "@patternfly/react-core", "version": "5.4.14", "direct": true },
 *     ...
 *   ],
 *   "dependencies": {
 *     "@patternfly/react-core@5.4.14": ["@patternfly/react-styles@5.4.1", ...],
 *     ...
 *   }
 * }
 */

import { execSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const jsRoot = resolve(__dirname, "..");

const shippedWorkspaces = [
  "@keycloak/keycloak-account-ui",
  "@keycloak/keycloak-admin-ui",
  "themes-vendor",
];

const filterArgs = shippedWorkspaces.map((w) => `--filter '${w}'`).join(" ");

const raw = execSync(
  `pnpm list --prod --json --depth Infinity -r ${filterArgs}`,
  { cwd: jsRoot, encoding: "utf-8", maxBuffer: 10 * 1024 * 1024 },
);

const data = JSON.parse(raw);

const packages = new Map();
const dependencies = new Map();
const directPackages = new Set();

function pkgKey(name, version) {
  return `${name}@${version}`;
}

function collect(deps, isDirect) {
  if (!deps) return;
  for (const [name, info] of Object.entries(deps)) {
    const version = info.version || "";
    if (!version || version.startsWith("link:")) {
      collect(info.dependencies, isDirect);
      continue;
    }
    const key = pkgKey(name, version);
    if (!packages.has(key)) {
      packages.set(key, { name, version });
    }
    if (isDirect) {
      directPackages.add(key);
    }

    if (info.dependencies && !dependencies.has(key)) {
      const childKeys = [];
      for (const [cn, ci] of Object.entries(info.dependencies)) {
        const cv = ci.version || "";
        if (cv && !cv.startsWith("link:")) {
          childKeys.push(pkgKey(cn, cv));
        }
      }
      if (childKeys.length > 0) {
        dependencies.set(key, childKeys);
      }
    }

    collect(info.dependencies, false);
  }
}

for (const entry of data) {
  collect(entry.dependencies, true);
}

const sortedPackages = [...packages.values()]
  .map((p) => {
    const key = pkgKey(p.name, p.version);
    return {
      name: p.name,
      version: p.version,
      ...(directPackages.has(key) ? { direct: true } : {}),
    };
  })
  .sort((a, b) => {
    const c = a.name.localeCompare(b.name);
    return c !== 0 ? c : a.version.localeCompare(b.version);
  });

const sortedDeps = {};
for (const key of [...dependencies.keys()].sort()) {
  sortedDeps[key] = dependencies.get(key).sort();
}

const manifest = {
  packages: sortedPackages,
  dependencies: sortedDeps,
};

let outputPath = resolve(jsRoot, "npm-sbom-manifest.json");
const outputIdx = process.argv.indexOf("--output");
if (outputIdx !== -1 && process.argv[outputIdx + 1]) {
  outputPath = resolve(process.argv[outputIdx + 1]);
}

writeFileSync(outputPath, JSON.stringify(manifest, null, 2) + "\n");
const directCount = sortedPackages.filter((p) => p.direct).length;
console.log(
  `Wrote ${sortedPackages.length} production npm packages (${directCount} direct) to ${outputPath}`,
);
