# B742 — A Task's Tmux Server Starts Fresh

```json
{
  "$schema": "issue-1.json",
  "id": "B742",
  "title": "A Task's Tmux Server Starts Fresh",
  "state": "Backlog",
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
