# B312 — A Machine Named By Its Host And Account

**Status:** Review.
**Size:** S - `MachineName`, `PoreloContext`, its caller in `TransportLifecycle`, one command and the tests.

**What must be true.** The name Porelo gives a transport as `--machine` is `<host>-<account>`, always and everywhere,
so two people's accounts on one host never meet on one name; an account whose transport already kept another name
keeps its accounts there, because the transport keeps the name it first used.

## Why

Decided by the operator: every person on a prepared
host is an account of their own on the host's shared
homeserver. Today `MachineName.hostName()` is the host name alone, so the second account's `enroll` meets the first
one's `@porelo.<host>`, and on a central server that is refused. Porelo never passes the `machine` a `setup`
answered: that may be the host's name alone, which a second account of the host would share, and the host's person
and room setup relies on `<host>-<account>`. Nothing marks a prepared host, and nothing needs to.

## Plan

Read in the code on 2026-10-28.

- `MachineName.hostName()` (`app-base`) is `InetAddress.getLocalHost().getHostName()`. `TransportLifecycle` adds it
  as `--machine` to every verb but `settings`, `part`, `admit` and `dismiss`, for a transport whose `describe` takes
  `machine`.
- **The plan:** `MachineName.of(host, account)` gives `<host>-<account>`, folded to lower case, and refuses a name
  outside a machine's name (letters, digits, `. _ -`, a letter or digit first, at most 63 characters) before a
  transport is asked; `MachineTransports` checks `admit` and `dismiss` against the same pattern.
  `hostName()` stays for the deploy-key and clearing notices. The name reaches `TransportLifecycle` and the command
  through `PoreloContext.machineName()`, so a test's context goes by a fixed name and no test depends on its host.
- `porelo talk machine` prints the name, for a project admin to list.
- **Red first:** two accounts on one host give two names; a `machine` that `setup` answered is not passed back.

## Declined in the reading

- **A `null` host or account folding into `"null"`:** declined, since every main caller is in a `@NullMarked`
  package that NullAway checks as an error, and tests pass literals.
- **The new transport tests skipped where the kernel keyring is refused, inside a task:** declined, since every vault
  test of `TransportConversationsTest` opens its vault through the keyring the same way, and moving one alone would
  make the class read two ways.

## Acceptance

- Seen to fail first, then green, in `app-messaging` tests.
- `doc/messages.md` says what the name is and that a name the transport once kept stays.
