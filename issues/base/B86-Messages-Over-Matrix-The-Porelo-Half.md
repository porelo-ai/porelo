# B86 — Messages Over Matrix, The Porelo Half

```json
{
  "$schema": "issue-1.json",
  "id": "B86",
  "title": "Messages Over Matrix, The Porelo Half",
  "state": "Analyzed",
  "size": "M",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "The machine in the account's name, so one login on two machines stays apart; the transport account kept per homeserver, not per scheme; a registration token by `vault put`; docs; a walk on two machines."
}
```

**Why this size.** Porelo's verbs are built; the vault entry per homeserver, and a walk on two machines.

**What must be true.** Several machines run one project on one central homeserver: each is admitted by a person,
each acts only on the accounts it made, and one can be revoked alone while the others keep messaging. One login on
two machines stays supported and kept apart, decided 2026-11-04: the machine is in the account's name.

Porelo's side has never run against a central server: `--login` to a transport whose `describe` lists
`"takes": ["login"]`, `setup`'s `admitted`, `login`, `address` and `room`, no task started while
`admitted` is false, and the daemon's `SetUpMessages(project)`. A central homeserver has no administrator: each
machine holds its own provisioning account there, with each task's password kept beside its token. The
transport is given the person's login to suffix its accounts; Porelo does not supply a machine identity for
account creation, so separate accounts for the same login on two machines are not assured by this code, and
`TransportLifecycle`'s comment says never a machine's name: that goes with this issue.

## Plan

Read in the code on 2026-11-04
(`apps/app-messaging/src/main/java/org/fuin/porelo/app/TransportLifecycle.java`):

- `--login` is passed to every verb but `settings`, `part`, `admit` and `dismiss` when `describe` lists `login` in
  `takes`; `admit` and `dismiss` still take `--machine` to name the other machine. `setup`'s `admitted`,
  `login`, `address` and `room` are kept in what is remembered (`Conversation`), and a task of a project whose
  machine is not admitted is not started (`TaskConversations`), with whom to let in from `notAdmitted`.
- **What a transport hands back is kept per scheme, not per homeserver**: `transport/<scheme>/account`,
  `transport/<scheme>/project/<project>` and `transport/<scheme>/task/<task>` in the vault. A machine with one
  project on its own homeserver and one on a central one would hand the central server's provisioning account
  the local one's token.

Steps:

0. **The machine in the account's name**: what a transport suffixes an account with names the machine beside the login,
   so the same login on two machines gets two accounts and each is revoked alone. With porelo-message-matrix, whose
   accounts it makes. Red first: a test in `TransportLifecycleTest` where two machines of one login are handed one name.
1. **The account entry by homeserver**: `transport/<scheme>/account/<key>`, where the key is what `setup` names
   (a field such as `"server"` in its answer, so Porelo never parses a homeserver URL); a vault holding the old
   `account` entry keeps it for the server it was made for. Red first: a test in `TransportLifecycleTest` with two
   projects on two homeservers, where the second `setup` is handed the first's account.
2. **The registration token**: use the existing credential lending from `describe`'s `credentials` and `lent`
   verbs: a person puts it with `porelo vault put` under `<entry>.<server>`, using the HTTPS host from the
   `homeserver` setting, and it is
   handed to `setup` as the variable the transport names, never printed. Red first: `setup` run without it
   is refused with the vault name to put it under.
3. **`doc/transports.md`** says the central mode: the account per machine, admission, revoking one machine.
4. **The walk** in Acceptance, on two rented machines and one central homeserver, as a scenario in the suite if
   the kit can lease two machines into one run, by hand with its log otherwise.

## Acceptance

- Two machines run one project on one central homeserver, under two logins or under one: a task of the same name on
  each gets an account of its own. Seen to fail: the second machine's `enroll` taking the first one's account over, or a transport
  accepting (not refusing with 78) a machine name another machine already holds.
- A machine a person has not let into the room starts no task of the project and says whom to let in and where;
  once admitted, it starts them.
- Removing a task on one machine deactivates that task's account and no other machine's.
- One machine's access is revoked alone, and the other machines' tasks keep messaging.
- No token appears on a command line, in a log, or in `vault list`, on either machine.

## To be checked

- Which name stands for the machine, and how it stays one on a host that is rented anew; and that `expel <login>`,
  which takes every account with that final login suffix, still takes them all (with porelo-message-matrix).
- The vault name a central homeserver's registration token is put under, and whether `describe` declares it
  (with porelo-message-matrix).
- Whether the central server's admin-room `deactivate` works as specified on a central Tuwunel, with a
  provisioning account per machine (porelo-message-matrix's agent's).
- What `setup` answers to key the account entry by - its homeserver, as the transport spells it, is the leaning
  (with porelo-message-matrix).
- Whether the kit can lease two machines into one scenario, or the two-machine walk stays by hand.
- Confirm that `TransportLifecycle` keeps a task's password from `enroll`'s `secrets` in
  `transport/<scheme>/task/<task>` and hands it back to `retire` on a central homeserver.
