# [B510](README.md#b510) — The Tasks Contract In Porelo-Wire

**Status:** Review. Built on 2026-11-08 at the operator's word, before the third trial move.
**Size:** S - the interface files move into `porelo-wire`'s resources, where the daemon reads them as before, with the
native image's resource entry and a test of the published jar.

**What must be true.** The published `porelo-wire` jar carries `org.fuin.porelo.Tasks1/*.varlink` from the next release
on. A client outside porelo, such as porelo-mcp's stand-in daemon, reads the real contract from it, never from a copy.
Until that release, porelo-mcp keeps its copy at a named porelo commit (`a1f237b1c`).

## Analysis

Read in the code on 2026-11-08:

- The files lay in `daemon/src/main/resources/varlink/org.fuin.porelo.Tasks1/`, and `daemon` is not published.
- `PoreloDaemon.description()` reads each part by an absolute path on the classpath,
  `/varlink/org.fuin.porelo.Tasks1/<part>.varlink`, in the fixed order of `PARTS`. A file that moves to another jar on
  the classpath is still found by the same path.
- The native image takes them through
  `daemon/src/main/resources/META-INF/native-image/org.fuin.porelo/porelo-daemon/reachability-metadata.json` (glob
  `varlink/org.fuin.porelo.Tasks1/*.varlink`). The entry moves with the files, into `porelo-wire`'s own metadata.
- `porelo-wire` is Apache-2.0 and holds the varlink code already. Within Tasks1 the contract only grows, so a client
  built against one release's files reads a later daemon.

## Plan

- Move the files to `wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/`. As built, the native image's resource
  entry stays in the daemon's metadata, with the list added: a glob there matches the files in any jar on the class
  path, and in porelo-wire's own metadata it would put them into every native image built on porelo-wire.
- Offer the part order beside them (a list file), so a reader outside the daemon puts the parts together as the daemon
  does. `PoreloDaemon.PARTS` reads that list too, so there is one order.
- As built after the reading, `porelo-wire` also reads them: `TasksContract.description()` and `parts()`, which the
  daemon serves and a client outside porelo calls. It takes a comment after a name on the list, refuses a name listed
  twice and a contract found twice on the class path (an older porelo-wire, or parts left in a module's classes), and
  ends each part's last line. The class path is read when the description is asked for, no longer when the daemon's
  class loads.
- **Red first:**
  - a test on porelo-wire's classes (`TasksContractTest`, before the jar is built) finds every part the list names,
    every carried part named once on the list, and each ending its last line;
  - the daemon's interface description is unchanged, byte for byte, from before the move. As built, checked once at
    the move (sha256 `b5e6efb5…` before and after) rather than held by a test, since the contract grows;
  - the native daemon: no dist check calls `GetInterfaceDescription`. `porelod` reads the description when it starts
    serving (`describedBy`), so a native `porelod` without it does not start, and the acceptance suite starts it.
