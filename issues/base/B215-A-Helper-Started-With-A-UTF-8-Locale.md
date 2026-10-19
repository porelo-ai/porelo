# B215 — A Helper Started With A UTF-8 Locale

**Status:** Idea.

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

## To be checked

- Whether `C.UTF-8` exists on every system Porelo supports outside an image; glibc has it built in since 2.35.
