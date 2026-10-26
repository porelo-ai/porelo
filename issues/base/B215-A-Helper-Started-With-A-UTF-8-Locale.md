# B215 — A Helper Started With A UTF-8 Locale

**Status:** Review: a helper started from an environment with no `LANG`, `LC_ALL` or `LC_CTYPE` gets `LANG=C.UTF-8`, seen failing first in `HelperEnvironmentTest`.
**Size:** XS - one file in `app-base`, `HelperEnvironment`, and its test; a change to `app-base` is said in the channel first.

**What must be true.** A transport and the message filter, which porelod starts, run with a UTF-8 locale even where
porelod has none, so an argument or a path with non-ASCII characters reaches them whole. A locale the machine sets
is never overridden.

## Why

Measured by the matrix repository on 2026-10-19, in a task image with no locale: the transport's
`enroll --display " Schreiberin Ä "` came back "Schreiberin ?". Java decodes a program's arguments and file names
by the locale, and in the C locale that is ASCII. Message text is not affected, since it goes by files and standard
input, read as UTF-8.

Porelo passes no person's words as an argument today, so nothing is lost now. But `HelperEnvironment` hands a
helper exactly the caller's `LANG`, `LANGUAGE`, `LC_ALL`, `LC_CTYPE` and `LC_MESSAGES`, and porelod gets them from
the systemd user manager. A host whose user manager has no locale would start every helper in the C locale. Then a
mailbox under a home path with an umlaut, or any later argument holding a name, would be read wrong.

## The shape proposed

- `HelperEnvironment` adds `LANG=C.UTF-8` when the caller has none of `LANG`, `LC_ALL` and `LC_CTYPE`, as a task's
  image already has `ENV LANG=C.UTF-8`. One that is set passes unchanged.
- A test of the empty environment, seen red first.

## Plan

Read in `HelperEnvironment`, `Command.withChosenEnvironment`, `porelod.service` and `Containerfile`.

1. `HelperEnvironment.base()` copies the caller's `LANG`, `LANGUAGE`, `LC_ALL`, `LC_CTYPE` and `LC_MESSAGES` as they
   are; every helper the messaging area starts goes through `chosen()` (the filter, the inbound check, each
   transport call). `porelod.service` sets no locale, so porelod has the user manager's.
2. After the copy, `LANG=C.UTF-8` is added when none of `LANG`, `LC_ALL` and `LC_CTYPE` is set and not empty, as a
   task image already has `ENV LANG=C.UTF-8`; one that is set, even `C`, passes unchanged.
3. **Red first:** `HelperEnvironmentTest.aCallerWithNoLocaleHandsAHelperCUtf8`, then
   `aLocaleTheCallerSetsPassesUnchanged`; the existing test of what passes gains `LANG`.

**Decided:**
- porelod's own locale is out of scope: only the helpers it starts get `LANG=C.UTF-8`.

## Acceptance

- A helper started from an environment with no locale gets `LANG=C.UTF-8`. Seen to fail first.
- `LC_ALL`, `LC_CTYPE` or `LANG` set passes unchanged and no `LANG` is added.

## To be checked

- That `C.UTF-8` is in `locale -a` on Debian 13 and Fedora, and that a native transport decodes a non-ASCII
  argument started so. Ubuntu 26.04 lists it as `C.utf8` (the host, 2026-10-26).
