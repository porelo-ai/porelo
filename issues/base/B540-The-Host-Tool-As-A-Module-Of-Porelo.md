# [B540](README.md#b540) — The Host Tool As A Module Of Porelo

**Status:** Review, since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.
**Size:** M.
**Blocked by:** -.

**What must be true.** A module `host/` of porelo, built by Agent Core and read by Agent Machines, builds the native binary `porelo-host` in porelo's reactor and
the package `porelo-host` (`.deb`, binary at `/usr/sbin/porelo-host` and its man page), signed and published as
porelo's packages; `porelo-host --version` answers porelo's version. The boundary every later verb uses is here:
running as root it reads no configuration from the environment and refuses a caller's environment beyond `LANG` and
`TERM`; the native image takes no JVM options from the environment (a test sets `JAVA_TOOL_OPTIONS` and
`_JAVA_OPTIONS` and sees no effect); every program through `SystemProgram`; a new root-owned-file check in `wire`
beside it; a verb acting for a person takes it from `SUDO_USER` alone and refuses without it.

## Why

Step 0 of the plan; package-check grows an install line for `porelo-host`. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief, the package by a second Codex run, reviewed by Agent Core.
- **`host/`** builds the native `porelo-host` in porelo's reactor with `-H:-ParseRuntimeOptions`, so no `-D` or `-X`
  before the verb reaches the image, and `--version` and `--help` stand alone (64 otherwise). The package
  `porelo-host` (`.deb` only) holds `/usr/sbin/porelo-host` and its man page in `/usr/share/man/man8`, published with
  porelo's packages; package-check installs it and sees `--version` answer porelo's version.
- **The environment** is an allow-list, not `LANG` and `TERM` alone: what sudo's `env_reset` and its default keep list
  set, and what systemd sets for a unit; any other name (`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`
  among them) is refused with 78, which package-check also sees on the installed binary. The program fixes its own
  locale (`Locale.ROOT`) and UTF-8 output, and every child gets a fixed environment.
- **Root:** the real uid must be 0 in the initial user namespace (`/proc/self/uid_map` read beside `Uid:`), else 77. A
  person's verb takes the person from `SUDO_USER` alone, whose account's uid must agree with `SUDO_UID` and be
  non-root, else 77 (64 when it runs without sudo).
- **Programs** go through `SystemProgram` by fixed root-owned path; ownership and mode are read without following
  links, and host uid 0 is found through `uid_map`, never trusting an unmapped owner's overflow uid. **Root's files** go
  through the new `RootOwnedFile` in `wire`: every component root's and not group- or other-writable, read without
  following links, the opened descriptor checked again.
- **From the code reading (another model):** the three grave findings (overflow and nested `uid_map` passing as root,
  `/proc` files and the check-to-open gap, `SystemProgram` following links) and the namespace `Uid: 0` are fixed with
  tests seen red first; the environment names sudo and systemd set by default, which the first list refused, were
  added.
- **Not built:** the caller's `umask` and resource limits are not reset, and a person's uid has no lower bound beyond
  agreeing with the account; each verb that writes or acts for a person states its own checks in its issue, from
  [B541](README.md#b541) to [B552](README.md#b552).
- **Tests:** `PoreloHostTest`, `HostEnvironmentTest`, `HostUidTest`, `HostPersonTest`, `HostProgramsTest`,
  `HostManPageTest`, `RootOwnedFileTest`, package-check's `HostInstallTest` and `DebianPublishingTest`; the stack's
  `host` module whole (1038) green, and package-check green on the shared test host.
