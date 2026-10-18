# B206 — A Transport Takes Its Secrets On Its Input

**Status:** Backlog.
**Size:** L - a change to the contract every transport implements, in porelo's eight places that run one and in each
transport, released together with a version both sides read.

**What must be true.** No secret Porelo keeps for a transport is in the environment of the transport's process.
Another process of the same account that reads `/proc/<pid>/environ` at any moment of a run finds none of them.

## Why

Today Porelo hands a transport the secrets it kept as environment variables (`doc/transports.md`, "A transport that
keeps a conversation"), through `HelperEnvironment.chosen` in `TransportLifecycle`, `TransportPoll`, `TransportSend`,
`TransportConversations`, `TransportDescription`, `InboundCheck` and `MessageFiltering`. A process's environment can be
read by every process of the same account until the process makes itself undumpable.

The matrix transport does that as the first step of `main`
([MX21](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx21)). On 2026-10-18 a shell
loop reading `/proc/<pid>/environ` right after each start still caught the token in 25 of 300 starts: the window
between `exec` and the `prctl` call is 15 to 40 ms, and the relay starts the transport often. That window is accepted
as a risk for now. This issue closes it.

## The shape, to be analysed

- **Secrets on standard input**, beside the settings that already go there: one JSON object
  `{"settings": {...}, "secrets": {...}}` instead of the settings alone. Or a file descriptor of its own (`3`) when a
  verb's standard input carries something else. The analysis checks which verbs read standard input today.
- **A version the transport declares in `describe`**, so Porelo knows which way to hand them over. A transport that
  declares none keeps the environment until every published transport has moved, and `doctor` names it.
- **Nothing else changes:** the environment keeps only `PATH`, `HOME`, the locale, `TMPDIR`, `XDG_*` and
  `DBUS_SESSION_BUS_ADDRESS`; secrets are still never arguments, never logged and never put into a task.
- The matching change in the matrix transport is an issue of porelo-message-matrix, filed with this one, linked
  here once it has its number.

## Acceptance

- [ ] A stand-in transport that copies its own `/proc/self/environ` into its output shows no secret, for every verb
  and for `poll` and `send`; the test is watched to fail against today's code.
- [ ] The matrix transport, released with the new version, runs the acceptance's message scenarios on both legs.
- [ ] A transport declaring the old way still works and is named by `doctor`.
