import { test } from "node:test";
import assert from "node:assert/strict";
import { requestHost } from "./request-host.ts";

const h = (entries) => new Headers(entries);

test("prefers x-forwarded-host — the proxy-preserved client host", () => {
  assert.equal(
    requestHost(h([["host", "web:3000"], ["x-forwarded-host", "octo.example.com"]])),
    "octo.example.com",
  );
});

test("falls back to host when no proxy header is present", () => {
  assert.equal(
    requestHost(h([["host", "admin-octo.example.com"]])),
    "admin-octo.example.com",
  );
});

test("takes the first entry of a chained x-forwarded-host", () => {
  assert.equal(
    requestHost(h([["x-forwarded-host", "octo.example.com, internal-proxy"]])),
    "octo.example.com",
  );
});

test("empty when neither header exists", () => {
  assert.equal(requestHost(h([])), "");
});
