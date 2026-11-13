# B742 — A Task's Tmux Server Starts Fresh

```json
{
  "$schema": "issue-1.json",
  "id": "B742",
  "title": "A Task's Tmux Server Starts Fresh",
  "state": "Analyzed",
  "size": "S",
  "priority": "High",
  "rank": 50,
  "blockedBy": [],
  "covers": "No setting an agent leaves in its tmux server (set-environment -g, default-shell, options) reaches the next start, which joins the `porelo` session.",
  "openQuestions": 1
}
```

**Why this size.** S: how the attach starts or joins its tmux server, in `Podman.sessionArguments`, and its tests.
Estimate: 45 minutes (Core's median for S).

**What must be true.** What an agent writes during a task never picks what Porelo starts as that agent next time.

Found while building [B738](README.md#b738). The agent runs inside tmux and can reach its server. If it keeps the
server alive with a detached session of its own, then `tmux set-environment -g` or `set -g default-shell` is still
in force at Porelo's next `new-session -A -s porelo`. Porelo's `-f /etc/porelo/tmux.conf` is read, and podman's
`--env` applied, only when a server starts.

## Plan

- Before an agent's start, when no `porelo` session is there, the start ends the task's tmux server, so the new
  session runs on a fresh server with Porelo's configuration and environment. How depends on the open question.
- First red, in `apps/app`: a server left running with `set-environment -g` of an agent variable and a changed
  `default-shell`; the next start's session has neither.

## Acceptance

- A tmux setting or environment variable the agent set in its server is gone at the next start.

## Open questions

- Ending the server also ends the agent's own background tmux sessions. Accept that, or give Porelo's session its
  own server (`tmux -L porelo` with a socket the agent's user cannot write)?
