# B344 — Messages Signed By A Task's Key

**Status:** Accepted.
**Size:** M - estimated 90 minutes from the median of M builds: the message checks, the machine key and enrolment
removed, the transport's settings, docs.

**What must be true.** A message a task sends is an SSH signature in the `porelo-message` namespace by its plain task
key ([B343](README.md#b343)); a receiver fetches the certificate from the host by the key's fingerprint, accepts the
message when a project certifier issued it and it is valid when the message arrives, and refuses it otherwise, naming
the key. The machine's own message key, `project enroll` and `machine-signers` go. A transport is given the person's
`login` in place of a machine name, `members` takes `--persons` ([B334](README.md#b334)) alone, and `--machines` goes.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A message signed by a task key whose certificate a certifier issued is accepted; one from another key, or arriving
  after its certificate expired, is refused and named.
- A transport's verbs are given `login`; no machine name is passed any more.
- Seen to fail first: today an incoming message is checked against `machine-signers`.
