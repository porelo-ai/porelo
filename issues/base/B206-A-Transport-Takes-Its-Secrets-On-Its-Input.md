# B206 — A Transport Takes Its Secrets On Its Input

**Status:** Review; its transport side, agreed on 2026-10-27, is the matrix transport's: it reads its secrets
from standard input.
**Size:** L - a change to the contract every transport implements, in porelo's four places that hand a transport
its secrets and in each transport, released together with a version both sides read.

**What must be true.** No secret Porelo keeps for a transport is in the environment of the transport's process.
Another process of the same account that reads `/proc/<pid>/environ` at any moment of a run finds none of them.

## Why

Today Porelo hands a transport the secrets it kept as environment variables (`doc/transports.md`, "A transport that
keeps a conversation"), through `HelperEnvironment.chosen` in `TransportLifecycle`, `TransportPoll`, `TransportSend`,
`TransportConversations`, `TransportDescription`, `InboundCheck` and `MessageFiltering`. A process's environment can be
read by every process of the same account until the process makes itself undumpable.

The matrix transport does that as the first step of `main`
. On 2026-10-18 a shell
loop reading `/proc/<pid>/environ` right after each start still caught the token in 25 of 300 starts: the window
between `exec` and the `prctl` call is 15 to 40 ms, and the relay starts the transport often. That window is accepted
as a risk for now. This issue closes it.

## Plan

Read in porelo at 2782c27a. Secrets go into a transport's environment at four places, not eight:
`TransportLifecycle.run` (every lifecycle verb; its standard input already carries the settings, `:817`),
`TransportConversations` (`poll` of a project `:318` and of a task's direct messages `:482`, `read` `:683`, `receipt`
`:773`), `TransportSend` (`send` with the acting task's secrets, `:212`) and `HelperAnswer` (`send` of Porelo's own
answers, `:79`). `TransportPoll` and `describe` hand none; `MessageFiltering` and `InboundCheck` run the message
filter, not a transport, and hand none either. No verb but the lifecycle's reads its standard input today (`doc/
transports.md`: "Nothing is read from standard input except a lifecycle verb's settings").

1. **One object on standard input, for every verb that is given secrets or settings:** `{"settings": {...},
   "secrets": {...}}`, `settings` only for a lifecycle verb, `secrets` only where Porelo kept some. Standard input
   rather than a descriptor of its own: no verb carries anything else there, and Java's `ProcessBuilder` hands a
   child no descriptor beyond the three.
   `secrets` is the whole map the transport printed and Porelo kept, key for key, `PORELO_MATRIX_CA_FILE` and the like
   among it: Porelo keeps no list of which keys are secret, and none of them goes into the environment. Standard
   input is closed once written, `poll`'s too (`ProcessCommandRunner.writeInput` does so today).
2. **The transport says it takes them so:** `describe` gains `"secrets": "input"`; absent, Porelo hands them over as
   today - the settings alone on standard input, the secrets in the environment - and `porelo doctor` names the
   transport as one whose secrets another process of the account can read. One class, `TransportInput`, builds the
   command for both ways from `TransportDescription`, and the four places use it, so no place can hand a secret the
   old way to a transport that takes the new one.
3. **The old way stays until every published transport has moved**, as this issue says: Porelo hands the
   environment to a transport that does not declare `input`, and `doctor` names it; dropping it is an issue of its
   own. The transport reads the environment only while its input holds no `secrets`, so it works with a Porelo of
   either way. Once its input holds `secrets`, it reads no secret from the environment, so a Porelo half-way between
   the two fails visibly; an object of only `settings` and `secrets` is the new way, any other the bare settings.
4. **No secret in a command's text:** `Command`, a record, prints its environment and input in `toString()`; it
   gets a `toString()` that names both by key only, so a log line built from a command holds no secret, whichever
   way it went.
5. **Red first:** `TransportInputTest`, a stand-in transport run by the real `ProcessCommandRunner`, writes its
   own `/proc/self/environ` and what it read on standard input; for `setup`, `poll`, `read`, `receipt` and `send`
   the secret is on the input and not in the environment. `TransportConversationsTest` runs its pass, receipt and
   relay-answer tests both ways and `TransportSendTest` a task's `send`, each asserting through one helper where
   the secret went; `CommandTest` asserts `toString()` holds no value; `DoctorCommandTest` names an old transport.
   Each was seen red with `TransportInput` forced to the old way, or before its change.
6. **Read against the skills, and what came of it:** a transport whose `describe` failed is handed no secret at
   all, neither way, so its verb fails rather than hand them out; the claim is kept among what a transport takes
   under a name no option can have; `doctor` names an old transport also beside one that did not answer, and only
   one that keeps a conversation, since no other is handed secrets; tests now check the settings inside the object,
   empty settings as `{}`, and `poll --direct` both ways.
7. **Docs:** `doc/transports.md` (the object, the `describe` field, the old way kept until its own change) and the
   changelog; `doc/security.md` does not name a transport's environment.

## Acceptance

- [ ] A stand-in transport that copies its own `/proc/self/environ` into its output shows no secret, for every verb
  and for `poll` and `send`; the test is watched to fail against today's code.
- [ ] The matrix transport, released with the new version, runs the acceptance's message scenarios on both legs.
  Measured on 2026-10-28 by Agent Matrix on the VM, against porelo at 2ef7cde6 and the transport that reads its secrets from standard input: setup, join,
  enroll, poll, receipt, send, retire and clear ran with no `PORELO_MATRIX_*` variable in their environment, and 5193
  samples of `/proc/<pid>/environ` found none readable.
- [ ] A transport declaring the old way still works and is named by `doctor`.
