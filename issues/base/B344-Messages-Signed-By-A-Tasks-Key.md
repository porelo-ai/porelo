# B344 — Messages Signed By A Task's Key

**Status:** Review. Accepted (operator, 2026-10-31).
**Size:** L - grown from M (estimated 90 minutes): beyond the message checks, the machine key's removal reached
`project enroll`, `talk key`, the `machine-signers` file and its protection, the transport verbs' `--login`, the
setup script and the acceptance features; about 70 files, +1630 / -1650 lines.

**What must be true.** A message a task sends is an SSH signature in the `porelo-message` namespace by its plain task
key ([B343](README.md#b343)); a receiver fetches the certificate from the host by the key's fingerprint, accepts the
message when a project certifier issued it and it is valid when the message arrives, and refuses it otherwise, naming
the key. The machine's own message key, `project enroll` and `machine-signers` go. A transport is given the person's
`login` in place of a machine name, `members` takes `--persons` ([B334](README.md#b334)) alone, and `--machines` goes.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Done

- A task signs each message it sends, at intake, with its own key read from the vault at that moment, an SSH signature
  in the `porelo-message` namespace. A locked vault leaves the message waiting in the outbox; a task with no vault or no
  certified key of its own has the message held, and its agent is told why in its feedback.
- A receiver traces the signing key by its fingerprint to its certificate: what this machine kept at the task's start,
  else the host's record (`porelo-host-person task --show`), whose answer must hold that very key. It believes the
  message when the certificate is for one task, issued by a certifier of the receiving task's project (the admitted
  persons' keys of its verified access commit; for a task without a project, the person who certified the receiver's own
  key) and valid at the moment the receiver's pass takes the message, by the receiver's clock, never a time the sender
  wrote. Anything else is held, the reason naming the key's fingerprint, and the certifier's where there is one.
- The certificate must name exactly one task, `task:<project>:<task>`, its project and task names such as a task can
  have, and never the room's `people`: a pattern, an empty part or a further colon is held. The host's record is
  believed only when the certificate it holds is of the key asked for, not only its own fingerprint field.
- A task whose project file cannot be read, or whose project cannot be told, believes nobody, never the person who
  started it. For a task without a project, whose certifier is in the vault, what arrives while the vault is locked
  waits in `inbound/` rather than being held.
- A message whose `metadata.from` names anyone but the peer its key traced to is held, since the agent reads the
  sender from there.
- A task of the same project is its task's name as a peer; a task of another project is its whole principal
  (`task:<project>:<task>`), so it never passes as a peer of the same name.
- `talk verify` checks each signature against the task key whose certificate is kept; whether it was believed is what
  its arrival decided. A helper's answer is signed with a key made for that answer alone.
- The machine's message key no longer signs anything; `talk key`, `project enroll` and `machine-signers` are gone, and
  `machine-signers` is no longer a protected file. A project being cleared still names a leftover `machine-signers` line
  of an earlier version.
- A transport that takes `login` is given `--login <the account's login>` on every verb but `settings`, `part`, `admit`
  and `dismiss`; a login that is no valid name is refused before the transport runs. A conversation records the
  transport's `login` in place of a machine. `members` is given `--project` and `--persons` alone, always; `--machines`
  is never passed. A person's words are taken without a join from a transport that offers `members` and hands them
  over (`"persons": true`). `porelo talk machine` prints the login a transport is given.
- Not claimed: the Varlink interface still answers `MessageKey` and names the machine's key line in `Clear` and
  `DeleteProject`, and its `machine` fields keep their name while they carry the login, since the frontend and the
  stand-in adapters read them; `admit` and `dismiss` still take `--machine`, which no transport that takes `login` offers
  any more; a task without a certified key, such as one without a gate, can no longer send a message; no test drives a
  real host's record.

## Acceptance

- A message signed by a task key whose certificate a certifier issued is accepted; one from another key, or arriving
  after its certificate expired, is refused and named.
- A transport's verbs are given `login`; no machine name is passed any more.
- Seen to fail first: today an incoming message is checked against `machine-signers`.
