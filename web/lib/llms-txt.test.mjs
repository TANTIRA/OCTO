import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { LLMS_TXT, isPublicLlmsLink, llmsTxtForHost, llmsTxtLinks, servesLlmsTxt } from "./llms-txt.ts";

const webRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

function publicDisallowPrefixes() {
  const source = readFileSync(join(webRoot, "app", "robots.ts"), "utf8");
  const listed = source.match(/disallow:\s*\[([^\]]+)\]/);
  assert.ok(listed, "robots.ts public disallow list");
  return [...listed[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}

test("llms.txt is not a static file, so the admin host cannot serve it", () => {
  assert.equal(existsSync(join(webRoot, "public", "llms.txt")), false);
});

test("the public host gets only the landing page and the contact anchor", () => {
  const decision = llmsTxtForHost("octo.mesta.click");
  assert.equal(decision.status, 200);
  assert.equal(decision.body, LLMS_TXT);
  assert.deepEqual(llmsTxtLinks(decision.body), [
    "https://octo.mesta.click/",
    "https://octo.mesta.click/#contact",
  ]);
  for (const link of llmsTxtLinks(decision.body)) {
    assert.equal(isPublicLlmsLink(link), true, link);
    const path = new URL(link).pathname;
    for (const prefix of publicDisallowPrefixes()) {
      const bare = prefix.endsWith("/") ? prefix.slice(0, -1) : prefix;
      assert.equal(path === bare || path.startsWith(`${bare}/`), false, `${link} vs ${prefix}`);
    }
  }
  assert.equal(decision.body.includes("/login"), false);
  assert.equal(decision.body.includes("api-octo"), false);
  assert.equal(decision.body.includes("admin-"), false);
});

test("the admin host is not given llms.txt", () => {
  for (const host of ["admin-octo.mesta.click", "admin-octo.example.com", " admin-octo.mesta.click"]) {
    assert.equal(servesLlmsTxt(host), false, host);
    const decision = llmsTxtForHost(host);
    assert.equal(decision.status, 404, host);
    assert.equal(decision.body.includes("octo.mesta.click"), false, host);
  }
});

test("a public host that is not the ops surface still serves the file", () => {
  assert.equal(servesLlmsTxt("octo.mesta.click"), true);
  assert.equal(servesLlmsTxt("octo.example.com"), true);
  assert.equal(servesLlmsTxt(""), true);
  assert.equal(llmsTxtForHost("octo.example.com").status, 200);
});

test("sign-in, the API root, and other origins are not public llms links", () => {
  for (const raw of [
    "https://octo.mesta.click/login",
    "https://octo.mesta.click/app",
    "https://octo.mesta.click/admin",
    "https://octo.mesta.click/api/",
    "https://api-octo.mesta.click",
    "https://api-octo.mesta.click/",
    "https://admin-octo.mesta.click/",
    "http://octo.mesta.click/",
    "https://octo.mesta.click/#platform",
  ]) {
    assert.equal(isPublicLlmsLink(raw), false, raw);
  }
});
