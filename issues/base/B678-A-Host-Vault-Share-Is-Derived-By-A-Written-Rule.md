# B678 — A Host Vault Share Is Derived By A Written Rule

```json
{
  "$schema": "issue-1.json",
  "id": "B678",
  "title": "A Host Vault Share Is Derived By A Written Rule",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 4,
  "blockedBy": [],
  "covers": "How a host vault's opening share is derived from a person's Ed25519 ssh key is written down in the docs with a fixed test vector (key, host fingerprint, login, signed data, signature blob, share) that porelo's own test pins, so another client derives the same 32 bytes and is checked without an agent."
}
```

**Why this size.** a section of the docs and one test over a fixed key; no code change.

Filed 2026-11-12 for the frontend's F290 (the operator's way 1), rewritten the same day: on Windows no `porelo` runs,
so the window unlocks a host itself, over its own ssh, with the host commands that exist (`porelo vault unlock`,
`init`, `rekey`, `clear`, each with `--share-stdin --json`). What it must match exactly is the share.

**What must be true.** The derivation `HostVaultShare.derive` makes is a written rule, and a test vector pins it:

- The text signed is `host=<SHA256 host key fingerprint> account=<login>`, UTF-8, one space.
- It is signed as SSHSIG with namespace `porelo-vault`: the Ed25519 key signs `"SSHSIG"`, then as ssh strings the
  namespace, an empty reserved string, `sha512`, and the SHA-512 of the text.
- The signature blob is the binary SSHSIG: `"SSHSIG"`, version 1, then as ssh strings the public key blob, the
  namespace, the empty reserved string, `sha512`, and the signature.
- The blob is verified against the key, then share = HKDF-SHA256 (input key material = that whole blob, salt empty,
  info `porelo-vault-share-v1`), 32 bytes.

Ed25519 signs deterministically, so a fixed key gives fixed bytes at every step.

## Plan

- A test key made for the vector only (never a person's), its public blob, a host fingerprint and a login; the test
  derives through `HostVaultShare.derive` and asserts the signed data, the blob and the share in hex.
- The docs' vault page gains the rule above and the vector, and the host steps: `vault unlock --share-stdin --json`
  with exactly 32 bytes, its answers `open` 0, `absent` 66, `other-key` 77, `not-a-host-vault` 65; `init` on absent;
  `clear --yes --force` only on the person's yes; `rekey` with 64 bytes, old share then new.
- Red first: the test is written with the vector's values before they are read off, and fails until they match.

## Acceptance

- The vector in the docs equals what the test asserts; a change to the derivation fails the test.

## Built (2026-11-12)

- `HostVaultShareVectorTest` in vault pins the vector: a key made for the vector only (the Ed25519 seed of the
  bytes 0x00 to 0x1f), host `SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s`, login `builder`. It derives
  through `HostVaultShare.derive` and asserts in hex the public blob, the signed data the key was asked to sign,
  the binary SSHSIG blob and the share; it also builds the signed data from the written rule, checks the share is
  the HKDF of the blob, and checks that `doc/credentials.md` states each value of the vector.
- Red first: the test ran with placeholder values and failed on each, and the real values were read off one at a
  time; the share was checked once more with an independent HKDF outside Java.
- `doc/credentials.md` has the section *How a host share is derived*: the rule in four steps, the vector, and the
  host steps (`vault unlock`, `init`, `clear --yes --force` and `rekey`, each with `--json`, their input, answers
  and exit codes). No production code changed.
