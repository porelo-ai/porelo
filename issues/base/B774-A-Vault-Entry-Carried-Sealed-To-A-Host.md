# B774 — A Vault Entry Carried Sealed to a Host

```json
{
  "$schema": "issue-1.json",
  "id": "B774",
  "title": "A Vault Entry Carried Sealed to a Host",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 131,
  "blockedBy": [],
  "covers": "A person copies an entry of this machine's vault into their vault on a host, the value sealed by this machine's daemon to a key the host's daemon made for that one copy, so no interface, log or command line ever holds it",
  "openQuestions": 0
}
```

**Why this size.** M: two daemon methods on the receiving side and one on the sending side, the sealing with the
JDK's own X25519 and AES-GCM, their tests, the contract and a docs section. Estimate: 63 minutes (Core's median for
M). Porelo's half of ST362's first source (a); the window's dialog is Agent Frontend's.

**What must be true.** A person who works on a host can take a credential they already keep on their own machine
there, from the window, without retyping it and without it appearing in the window, a log, a command line or a file
outside the two vaults.

Filed 2026-11-14 under ST362 "Credentials carried to a host", from the operator's word.

## Why

A host's vault starts empty. Today a person types each provider's key again on the host (`porelo vault store` over
ssh) or signs an agent in there. The window already holds a connection to this machine's daemon and to the host's;
what it lacks is a way to move one entry between the two vaults that never hands it the value.

## Plan

The window carries a sealed blob between the two daemons; only they ever hold the value.

1. Host, `CredentialReceiver() -> (key: string, until: string)`: the host's daemon makes an X25519 key pair for one
   copy, keeps the private half in memory only, for five minutes or until used, and answers the public half (base64)
   and its end. At most a few at once; a locked vault refuses (`VAULT_LOCKED`) before a key is made.
2. This machine, `SealCredential(name: string, key: string) -> (sealed: string, kind: string)`: reads the vault entry
   `name` (its value and kind), seals it to `key` (ephemeral X25519 agreement, HKDF-SHA256, AES-256-GCM, the entry's
   name and kind as associated data) and answers the blob. A locked vault or a missing entry answers its outcome and
   no blob. A key that is not a 32-byte X25519 public key is refused.
3. Host, `ReceiveCredential(sealed: string, name: string, kind: string, replace: bool) -> (outcome: string)`: opens
   the blob with the held key (one use: the key is gone after it, whatever the outcome), checks the name and kind
   against the associated data, and stores the entry in the host account's vault. An entry already there is kept
   unless `replace`: `EXISTS`. Outcomes `STORED`, `EXISTS`, `VAULT_LOCKED`, `NO_RECEIVER` (expired or used),
   `REFUSED` (does not open).
4. Neither daemon logs the value or the blob; the methods' audit lines name the entry and the outcome only. The
   contract comments say the blob is sealed to the receiver and the window must not keep it.
5. `doc/hosts.md`: a section "A credential carried to a host"; `doc/security.md`: the sealing and why the window
   never holds the value. Changelog entry.

## Built

- `vault`: `CredentialSeal` - X25519 (the JDK's), HKDF-SHA256, AES-256-GCM; associated data
  `porelo credential\0<name>\0<kind>`; the blob is a version byte, the sender's 32-byte public half, a 12-byte nonce
  and the ciphertext, at most 1 MiB. A low-order key that agrees on zero is refused.
- `daemon`: `CredentialCarrying` - `seal` on the person's machine, and `Receiving` on the host: receiving keys in
  memory, five minutes each, at most 4 (a newer one ends the oldest). Registered in `VaultMethods` as
  `CredentialReceiver`, `SealCredential`, `ReceiveCredential`; all three in `McpMethods.NEVER`.
- Wire, as posted to the room and wider than the plan: `CredentialReceiver() -> (outcome, key, until)` with `READY`
  or `VAULT_LOCKED`; `SealCredential(key, name) -> (outcome, sealed, name, kind, detail)` with `SEALED`,
  `NO_SUCH_ENTRY`, `VAULT_LOCKED`, `BAD_KEY`, `TOO_LARGE`; `ReceiveCredential(sealed, name, kind, replace) -> (outcome, detail)`.
  [B775](README.md#b775) adds its parameters and outcomes to `SealCredential`.
- Unlike the plan's "whatever the outcome": a blob carries no key's id, so the host tries each live key, and only the
  one that opens it ends. A blob that opens with none answers `REFUSED` and ends no key, so a stray call cannot end a
  copy in flight. The same blob a second time answers `NO_RECEIVER` (or `REFUSED` while another key lives).
- No log line in either method; the answers carry name, kind and outcome, never the value.
- Docs: `doc/hosts.md` "A credential carried to a host", `doc/security.md` the same; contract comments in
  `30-vault.varlink`. Changelog `credential-carried-sealed-to-a-host.yml`.
- Red first: `CredentialSealTest` and `CredentialCarryingTest` were written before their classes (compile red).

### Reading (Codex gpt-6.1-sol, one reading)

- Red, fixed: name and kind were bound joined by NUL, so ("a\0b", "") opened as ("a", "b\0"); each part is
  length-prefixed now. `aNameAndKindCannotTradeANulBetweenThem`, seen failing first.
- Red, fixed: an entry sealed to more than 1 MiB, which no receiver opens; it is refused at the seal now
  (`TOO_LARGE`), and a sealed form longer than that is refused before it is decoded.
  `anEntryTooLargeToOpenIsNotSealed`, seen failing first.
- Red, fixed: a key still opened at exactly five minutes. `aReceivingKeyAtItsFiveMinutesIsGone`, seen failing first.
- Yellow, fixed: the shut-vault socket test had no vault at all; it writes one now. Added a low-order key and a
  changed byte or version, which held already. The contract and docs say an ended key is dropped at the next call,
  and that the window keeping nothing is its own promise.
- Yellow, left: no RFC 7748 test vectors; the round trips and the low-order refusal stand for them.

## Red-first test

- Seal on one context, receive on another: the host's vault holds the same value and kind; the blob does not
  contain the value's bytes.
- A second `ReceiveCredential` with the same blob answers `NO_RECEIVER`; one after five minutes (a fixed clock) too.
- A blob sealed to another key, or with a changed name, answers `REFUSED` and stores nothing.
- An existing entry answers `EXISTS` without `replace` and is replaced with it.

## Risks

- A blob is a secret's ciphertext in the window's memory for a moment; it is useless without the host's private
  half, which never leaves the host's daemon and dies with the copy.
- The window could seal to a key a malicious host gave: the host is the person's own, reached over the pinned
  connection the window already trusts for everything else on it.

## Blockers

None. [B775](README.md#b775) adds the second source on this one.

## Acceptance

- The red-first tests pass; the vault and daemon tests still pass.
- On the VM as host: an entry of this machine's vault is carried to it, and a task there uses it.

## Open questions

None.

## To be checked

- That an interface's crash between seal and receive leaves nothing behind but an expiring key in the host's memory.
