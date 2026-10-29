# B314 — A Project Admin Keeps The Room's Members To The List

**Status:** Implementing.
**Size:** S - one `talk` command, one daemon call, a call after each applied commit, docs and tests,
on top of the transport's two verbs.

**What must be true.** The project admin's Porelo makes the project's room on a central homeserver with `porelo talk
room P`, and after
each applied commit of the project or its access file it keeps the room's machines to the applied developer list;
only the account that made the room does so.

## Why

Decided by the operator: on a prepared host, the
project admin's Porelo holds the room and admits and removes
developers' machines. With the list in the signed access file ([B310](README.md#b310)), the room follows it with no hand
of anyone.

## Plan

Read in the code on 2026-10-28.

- `TransportLifecycle` runs a transport's verbs where its `describe` offers them; `MachineTransports` holds the
  per-project ones (`admit`, `dismiss`). `porelo talk members` already exists and lists the room's people; it keeps
  its meaning.
- **The plan:**
  - `porelo talk room P` passes the transport's `setup --create-room`, and records that this account made the room.
  - After each applied commit (`Reconcile.recordApplied`'s place, [B310](README.md#b310)), an account that made the room
    calls the
    transport's `members --project P --machines <host>-<account>,...` from the applied list ([B312](README.md#b312)'s
    names), and
    logs what it invited and removed.
  - The daemon gets `CreateRoom`, additive, in `45-messaging.varlink`.
  - Each refused where `describe` does not offer the verb.
- **Red first:** a fake transport records `members` with the list after an applied commit, and nothing in an account
  that did not make the room.

## Acceptance

- Seen to fail first, then green, in `app-messaging` tests.
- An acceptance scenario on the VM with the matrix transport, once its verbs exist.

## To be checked

- The transport's verbs are porelo-message-matrix's: `setup --create-room` and
  `members --project P --machines M1,M2,…`, declarative and idempotent, both offered by `describe`.
