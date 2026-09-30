import { test } from "node:test";
import assert from "node:assert/strict";
import { isPlatformAdmin } from "./admin-gate.ts";

test("only a literal platformAdmin: true opens the ops surface", () => {
  assert.equal(isPlatformAdmin({ userId: "u", tenants: [], platformAdmin: true }), true);
});

test("everything else fails closed", () => {
  for (const body of [
    { userId: "u", tenants: [], platformAdmin: false },
    { userId: "u", tenants: [{ tenantId: "t", slug: "s", role: "admin" }] }, // tenant ADMIN is not platform admin
    { platformAdmin: "true" },
    { platformAdmin: 1 },
    {},
    null,
    undefined,
    "platformAdmin",
  ]) {
    assert.equal(isPlatformAdmin(body), false, JSON.stringify(body));
  }
});
