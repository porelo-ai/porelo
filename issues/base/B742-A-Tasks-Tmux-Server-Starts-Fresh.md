# B742 — A Task's Tmux Server Starts Fresh

```json
{
  "$schema": "issue-1.json",
  "id": "B742",
  "title": "A Task's Tmux Server Starts Fresh",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 50,
  "blockedBy": [],
  "covers": "No setting an agent leaves in its tmux server (set-environment -g, default-shell, options) reaches the next start, which joins the `porelo` session.",
  "openQuestions": 0
}
```

**Why this size.** M: Porelo's session moves to its own tmux server, whose socket the agent cannot write. Every exec
that starts, joins or reads the session changes, with a measurement of who may reach the server. Estimate:
63 minutes (Core's median for M), p80 229.

**What must be true.** What an agent writes during a task never picks what Porelo starts as that agent next time.

Found while building [B738](README.md#b738). The agent runs inside tmux and can reach its server. If it keeps the
server alive with a detached session of its own, then `tmux set-environment -g` or `set -g default-shell` is still
in force at Porelo's next `new-session -A -s porelo`. Porelo's `-f /etc/porelo/tmux.conf` is read, and podman's
`--env` applied, only when a server starts.

## Plan

The operator decided on 2026-11-13: Porelo's session gets its own tmux server (`tmux -L porelo`, on a socket the
agent cannot write), and the agent's own tmux sessions are left alone.

- **Every tmux call of Porelo's names that server.** This covers the session of start, attach and resume
  (`Podman.sessionArguments`), and every read or send to it, such as the activity probe. The server's socket lives in
  a directory the agent's user cannot write.
- **The agent cannot reach the server.** The agent runs with `TMUX` and `TMUX_PANE` cleared, and the socket is closed
  to the agent's user. Measure first: the server runs as the agent's user, so check in the image whether tmux's
  `server-access` and the socket directory's owner and mode keep that user's other clients out, while Porelo's exec
  can still join. If they cannot, the server runs under an account of its own in the image. That would be a redesign:
  stop and say so.
- **A fresh server for each start without a `porelo` session**, so `-f /etc/porelo/tmux.conf` and podman's `--env`
  always apply.
- First red, in `apps/app` and `runtime`: an agent sets `tmux set-environment -g` and `set -g default-shell` on its
  own server. The next start's session has neither, and the agent's own session still runs.

## Acceptance

- A tmux setting or environment variable the agent set in any server it can reach is absent at the next start.
- The agent cannot connect to Porelo's tmux server; its own tmux sessions are left running.

## Answered

- Porelo's session gets its own tmux server, on a socket the agent cannot write, and the agent's own sessions are
  left alone (the operator, 2026-11-13).

## Built

Step B of the plan: Porelo's session moves to a tmux server of its own, which every start without a live session
clears first. The second acceptance point stays open.

- **A server of Porelo's own.** Every tmux call of Porelo's names `tmux -L porelo`: the start, attach and resume
  (`Podman.SESSION_START` through `Podman.sessionArguments`, which `task attach` now uses too), the screen read, the
  keystrokes sent (`Podman.screen`, `type`, `enter`), the session probe in `TaskSession`, the activity read in
  `AgentWaiting`, the screen drawer in `ScreenFile` and the acceptance kit's reads. `Podman.tmux(...)` builds the
  call. The agent's own tmux servers are other servers and are left alone.
- **Cleared at a start without a session.** `SESSION_START` asks `has-session -t porelo`. When there is none, it ends
  the `porelo` server with `tmux -L porelo kill-server`, by its name and nothing else, and then runs `new-session -A`
  with `-f /etc/porelo/tmux.conf`. An option, hook, `set-environment -g`, `default-shell` or detached session the
  agent planted on that server ends with it, and the new server reads Porelo's configuration and podman's
  environment. A live session is joined and its server kept, so attach and resume go on working. The kill runs
  in the same exec as the start, as the agent.
- **Still open: the agent can reach the server.** The server runs as the agent, which owns its socket, and tmux's
  `server-access` cannot deny a server's own user. Between two starts the agent can still connect, set options and
  type into the live session. Closing that needs the server to run as another user (A1 below).
- **A task whose session predates this** runs on the default server, which `-L porelo` does not find. A start or an
  attach then makes a new session beside it. No fallback to the default server was added: the agent could plant a
  session named `porelo` there.
- **Measured for A1** (the server as root, each pane dropped to the agent with `setpriv`), with podman 5.7 on this
  machine:
  - Under `--cap-drop ALL`, `setpriv --reuid/--regid/--init-groups` fails with EPERM. With
    `--cap-add SETUID,SETGID` it works.
  - `setpriv` is in ubuntu as it is. Fedora needs `util-linux`. Alpine needs the `setpriv` package, because
    busybox's has no `--reuid`.
- Tests, red first:
  - `PodmanTest` runs `SESSION_START` against a fake tmux. A start without a session clears the server before
    `new-session`; one with a live session does not. Every read and keystroke names Porelo's server.
  - `ScreenFileTest` and `TaskAttachCommandTest` pin the server and the shared start.
  - Before the change: `PodmanTest` 78 run, 5 failed (`b742-red2.log`); `ScreenFileTest` 4 run, 1 failed;
    `TaskAttachCommandTest` 13 run, 1 failed (`b742-red.log`).
  - After it, all pass: `PodmanTest` 78, `ContainerfileTest` 37, `TaskRunCommandTest` 301, `AgentWaitingTest` 21,
    `TaskSessionTest` 12, `TerminalAttachTest` 12, `ScreenFileTest` 4, `TaskListingScreensTest` 1
    (`b742-green.log`), and `TaskAttachCommandTest` 13 with `handsTheTerminalToTheShell`, both re-pinned to the shared
    start (`b742-green2.log`). The acceptance kit compiles.
