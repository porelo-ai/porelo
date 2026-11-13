# B742 — A Task's Tmux Server Starts Fresh

```json
{
  "$schema": "issue-1.json",
  "id": "B742",
  "title": "A Task's Tmux Server Starts Fresh",
  "state": "Review",
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

Built in two steps. Step B gave Porelo's session a tmux server of its own, cleared at every start without a live
session. A1 (the operator's choice of 2026-11-13) moves that server to root and drops each pane to the agent.

- **Cleared at a start without a session.** `Podman.SESSION_START` asks `has-session -t porelo`. When there is none,
  it ends Porelo's server with `kill-server`, by its socket and nothing else, and then runs `new-session -A` with
  `-f /etc/porelo/tmux.conf`. An option, hook, `set-environment -g`, `default-shell` or detached session left on that
  server ends with it, and the new server reads Porelo's configuration and podman's environment. A live session is
  joined and its server kept, so attach and resume go on working. `task attach` uses the same start.
- **The server is root's.** Every tmux call of Porelo's is `--user root` and `tmux -S /run/porelo-tmux/tmux`:
  - the start, attach and resume (`sessionArguments`);
  - the session probe in `TaskSession` and the activity read in `AgentWaiting`, through `Podman.askSession`;
  - `screen`, `type` and `enter`;
  - the screen writer in `ScreenFile`, which now captures as root itself; the agent's drawer is gone;
  - the acceptance kit's reads.

  The start makes `/run/porelo` mode 0700, and the image makes it too.
- **Each pane drops to the agent.** The image writes a root-owned `/etc/porelo/as-agent`. It clears `TMUX` and
  `TMUX_PANE`, sets `HOME`, `USER`, `LOGNAME` and `SHELL` from `/etc/passwd`, and finds `setpriv` on system paths
  only. It then runs the agent's command, or its login shell, under `setpriv --reuid/--regid/--init-groups`.
  The session's first window goes through it, and `default-command` in the required configuration sends every new
  window through it too. podman's `--env` values reach the pane, since the server starts from the exec's environment.
- **The image.** `setpriv` is in every image: ubuntu as it is, `util-linux` on Debian-family and fedora, the `setpriv`
  package on alpine (busybox's has no `--reuid`). `Containerfile.TOOLS` names `util-linux`. A base without it fails
  the build with a message naming it.
- **The container's flags.** `ContainerSpec` adds `--cap-add SETUID,SETGID` after `--cap-drop ALL`; no-new-privileges
  and `--init` stay. The container is created with `--user root`. Measured with podman 5.7, a container whose user is
  not root gets the added capabilities in the ambient set of every exec, and the agent ran
  `setpriv --reuid=0 --regid=0 --clear-groups id` to `uid=0(root)`.
- **The exec sweep.** The container's default user is now root, so every exec names its user. `Podman.exec(user, ...)`
  is the one place an exec is spelled:
  - `--user agent`: the unattended run, attach, `ask`, `tee`, `mkdir`, `chmod`, `infocmp` and the session-ended check;
  - `--user root`: the session, the tmux calls and the file hand-ins.

  `PodmanTest.noExecInPodmanOrTheAppsLeavesItsUserToTheContainer` reads the sources of `runtime` and `apps` and fails
  on any other exec. The acceptance features' and kit's `podman exec` lines now name `--user agent`, or `--user root`
  for the tmux reads.
- **The configuration the server reads.** It is root's alone:
  - `/etc/porelo/tmux.conf`, `tmux-required.conf`, `as-agent` and every directory above them are made by the image
    as root, 0644 and 0755;
  - a person's own `account-tmux.conf` reaches the container as a root-owned 0644 copy, written by a root exec.

  Before the server starts, `SESSION_START` checks each of them, `/`, `/etc`, `/etc/porelo`, `/run` and
  `/run/porelo` (`Podman.ROOT_ALONE`). A link, an owner other than root, or a group or other write bit refuses the
  start and names the path, and no tmux is asked anything.
- **Tasks made before A1** (the operator, 2026-11-13): "tasks made before A1 don't matter, and there is no backward
  compatibility." There is no refusal, no hint and no old-server path for them.
- **Risks:**
  - *The pty is root's.* A pane's terminal device belongs to root. A program that reopens it by its `/dev/pts` name
    is refused; its open descriptors and `/dev/tty` work.
  - *A person's own tmux.conf runs in a root server.* A `run-shell` line in it runs as the container's root. That file
    is the person's own, copied from their account as root, never one the agent writes.
  - *A new exec without a user would run as root* with the two capabilities. The guard test is the bound.
  - *Not checked:* whether Debian trixie ships `setpriv` outside `util-linux`. If so, its build fails loudly.
- **Docs:** `doc/security.md` gains "The container's user and capabilities". `doc/decisions.md` gains "A task's
  session runs as root, and every pane drops to the agent". `ContainerSpec`'s comment names the two capabilities and
  why.
- Tests, red first:
  - Step B, before the change: `PodmanTest` 78 run, 5 failed (`b742-red2.log`); `ScreenFileTest` 4 run, 1 failed;
    `TaskAttachCommandTest` 13 run, 1 failed (`b742-red.log`).
  - A1, the two new `ContainerSpecTest` cases on the step-B tree: 17 run, 2 failed (`b742-a1-red.log`). The other new
    tests use the new constants and methods, so they do not compile there.
  - A1, after the change: runtime `PodmanTest` 82, `ContainerSpecTest` 17, `ContainerfileTest` 37,
    `ImageSettingsTest` 56, and `TaskSessionPrivilegeTest` 1, which builds a real ubuntu image with podman 5.7 and
    skips without podman or without `-Dporelo.test.podman.build=true`. All pass (`b742-a1-runtime.log`,
    `b742-a1-runtime2.log`). It checks:
    - the pane runs as `agent` with `CapPrm`, `CapEff` and `CapAmb` 0, `NoNewPrivs` 1 and no `TMUX`;
    - an agent exec likewise, and `setpriv --reuid=0` fails for it;
    - the session runs on root's socket, and the agent cannot list it;
    - the agent cannot write, replace or add to the configuration, `as-agent` or `/run/porelo`.
  - A1 in `apps/app`, all passing (`b742-a1-apps.log`):
    - 301: `TaskRunCommandTest`;
    - 28: `AgentWakeTest`;
    - 21 each: `AgentWaitingTest`, `HandInsTest`;
    - 17: `SetupScriptChannelTest`;
    - 15: `TaskCertificatesTest`;
    - 14 each: `CheckoutApprovalTest`, `EventLogTest`;
    - 13: `TaskAttachCommandTest`;
    - 12 each: `TaskSessionTest`, `TerminalAttachTest`;
    - 8: `TaskRunLimitTest`;
    - 6: `AppArmorEntryTest`;
    - 3 each: `ScreenFileTest`, `TaskRepositoriesTest`, `AccountTmuxTest`;
    - 1: `TaskListingScreensTest`.

    In the acceptance kit, `LiveTaskStepsTest` 17 also passes.
- Corrected after the legs went red (2026-11-14): the server's directory was `/run/porelo` itself, and at 0700 it hid
  everything mounted under it from the agent: its guide, mail, signing shim, screen and result. The server now has
  its own `/run/porelo-tmux`; `TaskRootOnlyDirectoryTest` holds that nothing handed to the agent lies under it.
