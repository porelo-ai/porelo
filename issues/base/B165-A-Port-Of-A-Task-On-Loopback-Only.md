# B165 — A Port Of A Task On Loopback Only

**Status:** Analyzed.
**Size:** M - a new command with a relay into the task's namespace, a journal, two documents and an acceptance step.

**What must be true.** `sokar task port TASK 8080` makes a server listening on that port inside the task reachable on
the machine's `127.0.0.1`, for the person at the machine only, by an explicit command, and journaled.

## Why

Whoever builds a web interface has to see the result before approving it, and today it can only be read as code.
Comparable tools forward a task's port or give it a preview address. Sokar keeps it on loopback: nothing of the task
becomes reachable from another machine.

## The shape

- Bound to `127.0.0.1` (and `::1`) only, on a free port the command names, never `0.0.0.0`; the forward ends with the
  command, or with `sokar task port TASK 8080 --stop`, and with the task.
- Nothing is reachable from the task towards the host by it: the forward goes one way, into the task.
- Each forward opened and closed is journaled with the task, the ports and who ran it.

## Plan

A relay of two halves, the reverse of the broker's `SocketRelay`: one in the task's network namespace connects to
`127.0.0.1:<port>` there, the other on the host listens on loopback. Between them a unix socket in the task's state
directory, which is `0700` and the host user's, so no other account and nothing in the task can reach it.

1. **Red first:** a unit test in `apps/app` that a forward's host listener is bound to `127.0.0.1` and `::1` and never
   to the wildcard address, and refuses a port below 1024 or one already taken, by name.
2. **`supervisor/.../PortRelay.java`** beside `SocketRelay`: accepts on the unix socket and connects each client to
   `127.0.0.1:<port>`. It is started inside the namespace as every helper there is, with
   `EgressPolicy.inNamespace(pid, ...)` (`podman unshare nsenter --net`), and recorded with `HelperPid` so poststop
   reaps it with the task.
3. **`apps/app-task/.../TaskPortCommand.java`**, added to `TaskCommand`'s subcommands: resolves the task, starts the
   inner half, binds the host half on `127.0.0.1` and `::1` (`--local <port>` to choose, the same number by default),
   prints the address, and runs until interrupted; `--stop` ends a forward opened by another shell, through a pid file
   beside the socket. A stopped task ends the forward: the inner half dies with the namespace and the host half
   closes on the broken socket.
4. **The journal:** one line per open and close in a file of `SokarPaths` beside `stopJournal()` (`ports.log`), with
   when, who (`user.name` and the terminal, as `TaskPanic` writes its stop line), the task, both ports. It moves into
   [B154](README.md#b154)'s log once that is built.
5. **The daemon:** none in this step. A forward lives as long as the command that holds it; a daemon method for the
   interface and the IDE plugin is a step of its own once they ask for it (see To be checked).
6. **Docs:** `doc/commands.md` (`task port`), `doc/reach.md` (under Reach: one way, into the task, loopback only).
7. **Acceptance:** a scenario in `acceptance/suite/.../task/task-commands.feature`: the stub task runs
   `python3 -m http.server 8080`; `sokar task port` answers on `127.0.0.1`, the machine's other address refuses the
   port, and `ports.log` names both ends.

## Acceptance

- Seen to fail first, then green: a server in a task answers on the forwarded loopback port; the same port is not
  reachable on the machine's other addresses; the journal names both ends.
- `doc/commands.md` and `doc/reach.md` say it.

## To be checked

- Whether the interface and the IDE plugin open the address for the person, over their ssh tunnel, which needs a
  daemon method that holds the forward instead of a command (Agent Frontend's and the IDE plugin's agent's, with
  Agent Core).
