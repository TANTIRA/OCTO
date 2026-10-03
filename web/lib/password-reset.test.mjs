import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MIN_PASSWORD_LENGTH,
  armRecoveryFor,
  clearRecoveryPending,
  isRecoveryCallback,
  markRecoveryPending,
  newPasswordError,
  recoveryPendingFor,
} from "./password-reset.ts";

test("a reset-link landing URL is a recovery callback", () => {
  assert.equal(
    isRecoveryCallback(
      "https://octo.example/login#access_token=a&expires_in=3600&refresh_token=r&token_type=bearer&type=recovery",
    ),
    true,
  );
  assert.equal(
    isRecoveryCallback("https://octo.example/login/reset?type=recovery"),
    true,
  );
});

test("other auth callbacks and plain URLs are not", () => {
  for (const href of [
    "https://octo.example/login",
    "https://octo.example/app#access_token=a&type=signup",
    "https://octo.example/app#access_token=a&type=magiclink",
    "https://octo.example/login#error=access_denied&error_code=otp_expired",
    "https://octo.example/login#type=recoveryx",
    "not a url",
    "",
  ]) {
    assert.equal(isRecoveryCallback(href), false, href);
  }
});

test("a new password must meet the minimum length", () => {
  const short = "a".repeat(MIN_PASSWORD_LENGTH - 1);
  assert.match(newPasswordError(short, short), /at least/);
  const ok = "a".repeat(MIN_PASSWORD_LENGTH);
  assert.equal(newPasswordError(ok, ok), null);
});

test("a new password cannot be whitespace only", () => {
  const blank = " ".repeat(MIN_PASSWORD_LENGTH);
  assert.match(newPasswordError(blank, blank), /spaces/);
});

test("the confirmation must match", () => {
  assert.match(
    newPasswordError("correct horse battery", "correct horse battery!"),
    /do not match/,
  );
});

// #549: the recovery-pending marker gates a reset-link session until the
// password is set. Armed before the link resolves (user unknown), narrowed
// once the session lands, cleared on set or sign-out.
function fakeStore() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
  };
}

test("an armed marker gates any session until it is narrowed or cleared", () => {
  const store = fakeStore();
  assert.equal(recoveryPendingFor(store, "user-a"), false);

  markRecoveryPending(store);
  // Just consumed: the gate runs before the reset page learns the user id.
  assert.equal(recoveryPendingFor(store, "user-a"), true);
  assert.equal(recoveryPendingFor(store, "user-b"), true);

  armRecoveryFor(store, "user-a");
  assert.equal(recoveryPendingFor(store, "user-a"), true);
  // A different user's session on the same browser is not gated.
  assert.equal(recoveryPendingFor(store, "user-b"), false);

  clearRecoveryPending(store);
  assert.equal(recoveryPendingFor(store, "user-a"), false);
});
