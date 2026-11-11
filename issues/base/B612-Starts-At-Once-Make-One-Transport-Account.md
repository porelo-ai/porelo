# B612 — Starts At Once Make One Transport Account

```json
{
  "$schema": "issue-1.json",
  "id": "B612",
  "title": "Starts At Once Make One Transport Account",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 20,
  "blockedBy": [],
  "covers": "Several tasks of one person started at once, before the person has an account on the project's transport, all join the conversation: one start makes the account and keeps its secret, the others take it."
}
```

**Why this size.** one lock in `TransportLifecycle.setup` around reading the kept secrets, the transport's `setup` and
keeping what it hands back; a unit test with a stand-in transport.

Filed 2026-11-11 from a rental run's finding.

**What must be true.** A person starts three tasks of a project on a host at the same time, and the person has no
account on the project's transport yet. All three come up. Today one start fails at "Joining the project's
conversation" with exit 78: the transport says the person's account exists with another password.

How it happens today, read in the code:

- `TransportLifecycle.setup` reads the secrets kept for the transport's account from the vault (`given`), runs the
  transport's `setup` with them, and keeps the `account` the transport hands back. Nothing holds a lock around these
  three steps; only `enroll` runs under the addresses file's lock.
- On a first start the vault holds no account secret, so each start gives the transport none. The matrix transport
  then makes a new random secret per run and derives the account's password from it. The first run registers the
  account; the next finds it taken under another password and refuses.

The transport cannot settle this alone: with no secret given it has nothing two runs share. The secret is Porelo's to
keep, so Porelo makes the first `setup` of an account happen once.

## Plan

- `TransportLifecycle.setup` holds a lock per transport and account (a lock file beside the transport's state, taken
  with `FileLocks.holding`, which works across processes) around `given`, the transport's `setup` and `keep`. `given`
  is read inside the lock, so a start that waited passes the account secret the first one kept, and the transport
  takes the existing account back.
- A failed `setup` releases the lock and keeps nothing, as today.
- Red first: a `TransportLifecycleTest` case starts two `setup`s of one project at once against a stand-in transport
  that hands back a new secret whenever none is given and refuses a second registration. Without the lock one fails;
  with it both pass and the vault holds one account secret.
- Changelog entry under *Fixed*.

## Acceptance

- The unit test above, seen red before the lock and green after.
- On a host, three tasks of one new person started at once all join the conversation.

## To be checked

- Whether a verb other than `setup` (`join`, `enroll`) can make the account too, and so needs the same lock.
- Whether the lock is per person's account or per transport alone. The vault is the person's, so a lock in the
  person's own state directory is per person already.
