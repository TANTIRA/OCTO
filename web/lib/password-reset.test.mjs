import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MIN_PASSWORD_LENGTH,
  RECOVERY_PENDING_KEY,
  accessTokenIsRecovery,
  decideRecoveryAccess,
  isRecoveryCallback,
  isRecoveryGrant,
  isRecoverySatisfied,
  newPasswordError,
  noteRecoveryGrant,
  recoverySessionKey,
  rememberRecoverySatisfied,
  tokenFingerprint,
} from "./password-reset.ts";

function memoryStore() {
  const map = new Map();
  return {
    getItem(key) {
      return map.has(key) ? map.get(key) : null;
    },
    setItem(key, value) {
      map.set(key, String(value));
    },
    removeItem(key) {
      map.delete(key);
    },
  };
}

function jwt(payload) {
  const body = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  return `eyJhbGciOiJub25lIn0.${body}.signature-segment`;
}

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

test("a recovery grant carries tokens or a code, not only type=recovery", () => {
  const token = "header.payload.signature";
  assert.equal(
    isRecoveryGrant(
      `https://octo.example/login/reset#access_token=${token}&refresh_token=r&type=recovery`,
    ),
    true,
  );
  assert.equal(
    isRecoveryGrant("https://octo.example/login/reset?code=abc&type=recovery"),
    true,
  );
  assert.equal(isRecoveryGrant("https://octo.example/login/reset?type=recovery"), false);
  assert.equal(
    isRecoveryGrant("https://octo.example/login#access_token=a&refresh_token=r&type=signup"),
    false,
  );
});

test("the grant marker is a fingerprint, not the access token", () => {
  const token = "header.payload.signature";
  const href = `https://octo.example/login/reset#access_token=${token}&refresh_token=r&type=recovery`;
  const store = memoryStore();
  noteRecoveryGrant(href, store);
  const marker = store.getItem(RECOVERY_PENDING_KEY);
  assert.equal(marker, tokenFingerprint(token));
  assert.equal(marker.includes(token), false);
  noteRecoveryGrant(href, store);
  assert.equal(store.getItem(RECOVERY_PENDING_KEY), marker);
});

test("a bare type=recovery URL does not mark a grant", () => {
  const store = memoryStore();
  noteRecoveryGrant("https://octo.example/login/reset?type=recovery", store);
  assert.equal(store.getItem(RECOVERY_PENDING_KEY), null);
});

test("recovery tokens are recognised from amr and session_id", () => {
  const recovery = jwt({
    amr: [{ method: "recovery", timestamp: 1 }],
    session_id: "sess-recovery",
  });
  const password = jwt({
    amr: [{ method: "password", timestamp: 1 }],
    session_id: "sess-password",
  });
  assert.equal(accessTokenIsRecovery(recovery), true);
  assert.equal(accessTokenIsRecovery(password), false);
  assert.equal(accessTokenIsRecovery("not-a-jwt"), false);
  assert.equal(recoverySessionKey(recovery), "sess-recovery");
  assert.equal(recoverySessionKey(password), "sess-password");
});

test("a reset-link session stays pending across tabs until the password is saved", () => {
  const landing = decideRecoveryAccess({
    tabPending: tokenFingerprint("header.payload.signature"),
    durablePending: null,
    sessionKey: "sess-recovery",
    tokenIsRecovery: false,
    fromRecoveryEvent: false,
    grantMatchesSession: true,
    satisfied: false,
  });
  assert.equal(landing.pending, true);
  assert.equal(landing.durablePending, "sess-recovery");

  const newTab = decideRecoveryAccess({
    tabPending: null,
    durablePending: landing.durablePending,
    sessionKey: "sess-recovery",
    tokenIsRecovery: false,
    fromRecoveryEvent: false,
    grantMatchesSession: false,
    satisfied: false,
  });
  assert.equal(newTab.pending, true);

  const saved = decideRecoveryAccess({
    tabPending: "sess-recovery",
    durablePending: "sess-recovery",
    sessionKey: "sess-recovery",
    tokenIsRecovery: true,
    fromRecoveryEvent: false,
    grantMatchesSession: false,
    satisfied: true,
  });
  assert.equal(saved.pending, false);
  assert.equal(saved.durablePending, null);
});

test("an ordinary signed-in session is not a reset session", () => {
  const ordinary = decideRecoveryAccess({
    tabPending: null,
    durablePending: "sess-recovery",
    sessionKey: "sess-password",
    tokenIsRecovery: false,
    fromRecoveryEvent: false,
    grantMatchesSession: false,
    satisfied: false,
  });
  assert.equal(ordinary.pending, false);
  assert.equal(ordinary.durablePending, "sess-recovery");

  const mismatchedGrant = decideRecoveryAccess({
    tabPending: "fp:deadbeef",
    durablePending: null,
    sessionKey: "sess-password",
    tokenIsRecovery: false,
    fromRecoveryEvent: false,
    grantMatchesSession: false,
    satisfied: false,
  });
  assert.equal(mismatchedGrant.pending, false);
  assert.equal(mismatchedGrant.tabPending, null);
});

test("a failed grant does not leave the tab marker behind", () => {
  const failed = decideRecoveryAccess({
    tabPending: tokenFingerprint("header.payload.signature"),
    durablePending: "sess-other",
    sessionKey: null,
    tokenIsRecovery: false,
    fromRecoveryEvent: false,
    grantMatchesSession: false,
    satisfied: false,
  });
  assert.equal(failed.pending, false);
  assert.equal(failed.tabPending, null);
  assert.equal(failed.durablePending, "sess-other");
});

test("satisfied recovery sessions are remembered", () => {
  const store = memoryStore();
  assert.equal(isRecoverySatisfied(store, "sess-recovery"), false);
  rememberRecoverySatisfied(store, "sess-recovery");
  assert.equal(isRecoverySatisfied(store, "sess-recovery"), true);
  assert.equal(isRecoverySatisfied(store, "sess-other"), false);
});
