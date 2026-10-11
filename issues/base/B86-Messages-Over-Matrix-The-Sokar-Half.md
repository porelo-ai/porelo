# B86 — Messages Over Matrix, The Sokar Half

**Status:** Analyzed; blocked by sokar-message-matrix [MX12](https://github.com/sokar-ai/sokar-message-matrix/blob/main/issues/README.md#mx12).
**Size:** M - Sokar's verbs are built; the vault entry per homeserver, and a walk on two machines.

**What must be true.** Several machines run one project on one central homeserver: each is admitted by a person,
each acts only on the accounts it made, and one can be revoked alone while the others keep messaging.

Sokar's side is built and has never run against a central server: `--machine` to a transport whose `describe`
lists `"takes": ["machine"]`, `setup`'s `admitted`, `machine`, `address` and `room`, no task started while
`admitted` is false, and the daemon's `SetUpMessages(project)`. A central homeserver has no administrator: each
machine holds its own provisioning account there, with each task's password kept beside its token. The
transport's side is [MX12](https://github.com/sokar-ai/sokar-message-matrix/blob/main/issues/README.md#mx12).

## Plan

Read in the code on 2026-10-11 (`apps/app-messaging/.../TransportLifecycle.java`):

- `--machine` is passed to every verb but `settings` when `describe` lists it in `takes`; `setup`'s `admitted`,
  `machine`, `address` and `room` are kept in what is remembered (`Conversation`), and a task of a project whose
  machine is not admitted is not started (`TaskConversations`), with whom to let in from `notAdmitted`.
- **What a transport hands back is kept per scheme, not per homeserver**: `transport/<scheme>/account`,
  `transport/<scheme>/project/<project>` and `transport/<scheme>/task/<task>` in the vault. A machine with one
  project on its own homeserver and one on a central one would hand the central server's provisioning account
  the local one's token.

Steps, after [MX12](https://github.com/sokar-ai/sokar-message-matrix/blob/main/issues/README.md#mx12):

1. **The account entry by homeserver**: `transport/<scheme>/account/<key>`, where the key is what `setup` names
   (a field such as `"server"` in its answer, so Sokar never parses a homeserver URL); a vault holding the old
   `account` entry keeps it for the server it was made for. Red first: a test in `TransportLifecycleTest` with two
   projects on two homeservers, where the second `setup` is handed the first's account.
2. **The registration token**: put by a person with `sokar vault put` under a name the transport's `describe`
   declares, and handed to `setup` as the variable it names, never printed. Red first: `setup` run without it
   is refused with the vault name to put it under.
3. **`doc/transports.md`** says the central mode: the account per machine, admission, revoking one machine.
4. **The walk** in Acceptance, on two rented machines and one central homeserver, as a scenario in the suite if
   the kit can lease two machines into one run, by hand with its log otherwise.

## Acceptance

- Two machines run one project on one central homeserver: a task of the same name on each gets an account of
  its own. Seen to fail: the second machine's `enroll` taking the first one's account over, or a transport
  accepting (not refusing with 78) a machine name another machine already holds.
- A machine a person has not let into the room starts no task of the project and says whom to let in and where;
  once admitted, it starts them.
- Removing a task on one machine deactivates that task's account and no other machine's.
- One machine's access is revoked alone, and the other machines' tasks keep messaging.
- No token appears on a command line, in a log, or in `vault list`, on either machine.

## To be checked

- The vault name a central homeserver's registration token is put under, and whether `describe` declares it
  (Agent Core's, with sokar-message-matrix's agent).
- Whether the central server's admin-room `deactivate` works as specified on a central Tuwunel, with a
  provisioning account per machine (sokar-message-matrix's agent's, in [MX12](https://github.com/sokar-ai/sokar-message-matrix/blob/main/issues/README.md#mx12)).
- What `setup` answers to key the account entry by - its homeserver, as the transport spells it, is the leaning
  (Agent Core's, with sokar-message-matrix's agent).
- Whether the kit can lease two machines into one scenario, or the two-machine walk stays by hand (Agent Core's).
