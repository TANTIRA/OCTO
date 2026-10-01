import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MIN_PASSWORD_LENGTH,
  isRecoveryCallback,
  newPasswordError,
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
