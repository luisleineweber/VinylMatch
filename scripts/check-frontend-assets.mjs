import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";

const root = "src/main/frontend";
const tracked = new Set(
  execFileSync("git", ["ls-files", "--", root], { encoding: "utf8" })
    .split(/\r?\n/u)
    .map((file) => file.replaceAll("\\", "/"))
    .filter(Boolean),
);

const textFiles = [...tracked].filter((file) => /\.(?:html|js)$/u.test(file));
const failures = [];

function trackedTarget(fromFile, reference, rootRelative = false) {
  const clean = reference.split(/[?#]/u, 1)[0];
  if (!clean || clean.startsWith("#") || /^(?:[a-z]+:|\/\/)/iu.test(clean)) return;
  const target = rootRelative
    ? path.posix.join(root, clean.replace(/^\/+/, ""))
    : path.posix.normalize(path.posix.join(path.posix.dirname(fromFile), clean));
  if (!tracked.has(target)) failures.push(`${fromFile} -> ${reference} (${target} is not tracked)`);
}

for (const file of textFiles) {
  const content = readFileSync(file, "utf8");
  if (file.endsWith(".html")) {
    for (const match of content.matchAll(/\b(?:src|href)\s*=\s*["']([^"']+)["']/giu)) {
      trackedTarget(file, match[1], match[1].startsWith("/"));
    }
    if (/<script\b(?![^>]*\bsrc\s*=)[^>]*>/iu.test(content)) {
      failures.push(`${file} contains an inline script blocked by the production CSP`);
    }
    if (/\s(?:style|on[a-z]+)\s*=/iu.test(content)) {
      failures.push(`${file} contains an inline style or event handler blocked by the production CSP`);
    }
  }
  if (file.endsWith(".js")) {
    for (const match of content.matchAll(/(?:\bfrom\s*|\bimport\s*)["'](\.[^"']+)["']/gu)) {
      trackedTarget(file, match[1]);
    }
  }
}

if (failures.length) {
  console.error("Frontend asset graph contains unresolved or untracked references:\n" + failures.join("\n"));
  process.exit(1);
}

console.log(`Frontend asset graph OK (${textFiles.length} tracked HTML/JS files checked).`);
