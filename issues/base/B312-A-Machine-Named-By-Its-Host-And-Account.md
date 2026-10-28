# B312 — A Machine Named By Its Host And Account

**Status:** Estimated, released by the operator on 2026-10-28 with this size; accepted the same day, from
[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78).
**Size:** S - `MachineName`, its callers in `TransportLifecycle`, one command and the tests.

**What must be true.** The name Porelo gives a transport as `--machine` is `<host>-<account>`, so two people's accounts
on one host
never meet on one name; an account whose transport already kept a name keeps passing that one.

## Why

[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78) (QB77 a): every person on a prepared
host is an account of their own on the host's shared
homeserver. Today `MachineName.hostName()` is the host name alone, so the second account's `enroll` meets the first
one's `@porelo.<host>`, and on a central server that is refused.

## Plan

Read in the code on 2026-10-28.

- `MachineName.hostName()` (`app-base`) is `InetAddress.getLocalHost().getHostName()`. `TransportLifecycle` adds it
  as `--machine` to every verb but `settings`, `part`, `admit` and `dismiss`, for a transport whose `describe` takes
  `machine`.
- **The plan:** `MachineName.of(context)` gives `<host>-<account>`, folded to lower case, and refuses an account name
  outside the transport's characters (letters, digits, `. _ = -`) before a transport is asked.
- **A name already kept wins:** where the transport's settings for the account hold `PORELO_MATRIX_MACHINE` (or the
  transport's answer at `setup` named one), that one is passed, so an account with a conversation keeps its accounts.
- `porelo talk machine` prints the name, for a project admin to list.
- **Red first:** two accounts on one host give two names; an account with a kept name passes it.

## Acceptance

- Seen to fail first, then green, in `app-messaging` tests.
- `doc/messages.md` says what the name is and that a name once kept stays.

## To be checked

- Where exactly the transport's kept name is read from: its settings file, or its `setup` answer.
