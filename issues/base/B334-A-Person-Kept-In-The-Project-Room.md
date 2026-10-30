# B334 — A Person Kept In The Project Room

**Status:** Review. Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** M - 45 minutes by the median of M builds. It grew from S while building: a developer let in by `members`
never joins, so their words in the room are taken by the host account the transport names, a second contract with
the transport.

**What must be true.** Every developer the applied access file names is in the project's room as a person, under
their own account on the host's homeserver, and a developer dropped from the access file leaves the room, as their
machine does. The room's maker keeps persons and machines to the list in the same act, after each applied access
commit.

The contract with the transport: a person is named by their host account, the key under `developers:` in the access
file, and that is also their login on the homeserver. The transport's `members` verb takes `--persons` with those
accounts, comma separated and sorted, beside `--machines`; Porelo gives it only when the transport's description
lists `persons` among the options it takes, so a transport without it keeps machines alone. Only the list itself
counts: `"persons": true` announced as a field of its own asks for people's words on `poll`, and gives `members`
nothing. Its answer carries
`persons_invited` and `persons_removed` beside `invited` and `removed`. Without `--persons` the transport leaves
every person as it is. Inviting and removing a person stays inside `members`: Porelo has no verb of its own for it.

A developer kept in the room this way is taken as a person at once, on every machine that hears the room, with no
`porelo talk join`. In a person's file from `poll --persons` the transport adds `"account": "<login>"` when the
sender is a person's own account on the project's server; Porelo takes the words when that account is a developer
of the applied access file who did not join under another account of the room, and keeps the sender as that
person's account in its members list, as `join` does, so a task can answer them. Without the field the sender must
have joined, as before.

## Plan

Read in the code on 2026-10-30: `MachineTransports.keepMembers` (`apps/app-messaging/`) reads the applied access
file and calls `TransportLifecycle.members` with the machines alone; `Reconcile.keepRoom` calls it after each
applied commit and `TalkRoomCommand` once the room is made, printing `invited` and `removed`. What a transport
takes is `TransportDescription.takes`, which holds its `takes` list and the options it announces as `true`.

1. **`TransportLifecycle.members` takes the persons beside the machines** and adds `--persons A,B` after
   `--machines` only when the transport's `takes` list itself names `persons` (`TransportDescription.names`);
   otherwise the call is today's.
2. **`keepMembers` passes the keys under `developers:`, sorted** (a `TreeSet`), the same list it builds the
   machines from.
3. **`talk room` prints `persons invited` and `persons removed`** on lines of their own, only when the answer
   carries them, so a transport without persons prints what it printed before.
4. **Tests in `MachineTransportsTest`**: a transport taking `persons` is given every developer and its answer is
   shown; one dropped from the access file is passed no more and shown removed; a transport not taking it gets no
   `--persons`.
5. **Docs**: the `members` row in `doc/transports.md` and `talk room` in `doc/commands.md`.
6. **`PersonsWords` takes a developer by `account`** (added while building): `TransportLifecycle.developers` reads
   the applied access file's developers where the transport's list names `persons`, and
   `TransportLifecycle.remember` keeps the sender under the lock `join` and `part` take.

## Done

- `members` is given `--persons` with every developer's account of the applied access file, sorted, after each
  applied project or access commit and on `talk room`, only where the transport's `takes` list names `persons`; with
  no developer left it is given `--persons ""`.
- `talk room` prints `persons invited` and `persons removed` on lines of their own when the answer carries them,
  with any control, format or line-separator character shown as `?`; the event log names them the same way.
- A person's words carrying `account` are taken from a developer of the applied access file who did not join under
  another account, on every machine, and never from a task's account, this machine's own account or a blank sender.
  The sender is kept for answering them under `transport/<scheme>/taken/`, apart from who joined, and counts only
  while the applied access file names them: inbound, outbound and `talk members` alike, so a developer it drops is
  neither heard nor answered from then on, and a later `talk join` is never undone by that record. A damaged record
  names nobody. Clearing and pruning a project remove it. Who is heard and which accounts are tasks' are read after
  each poll, waits included, and a developer taken earlier in a batch holds their account against a second sender.
- Not changed: an access file may name an account beginning with `_`, which a machine name refuses; such a project's
  room was not kept before this change either.
- Tests: `MachineTransportsTest` (persons given, dropped, the last one dropped, a field-only transport given none,
  an access commit alone, a forged line, the event log cleaned on both its lists, a developer's words taken without
  a join and handed as theirs, one the access file drops forgotten before any pass, a second sender in one batch,
  this machine's own account, a list naming no persons, a join not undone, a damaged record, clearing, a direct chat
  and its wait, a machine that did not make the room) and `PersonsWordsTest` (taken by account, an account the
  access file does not name, one who joined under another account, a task's account, a blank sender, no access
  file).

## Acceptance

- After an applied access commit, `members` is called with every developer's account as a person, and `talk room`
  shows the persons invited and removed on lines of their own.
- A developer dropped from the access file is passed no more, and the transport's answer names them removed.
- Against a transport whose description does not list `persons`, `--persons` is not given and machines are kept as
  before.
- Seen to fail first: today's `keepMembers` passes machines alone.
