# B741 — An Agent Never Replaces What Starts It

```json
{
  "$schema": "issue-1.json",
  "id": "B741",
  "title": "An Agent Never Replaces What Starts It",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 50,
  "blockedBy": [],
  "covers": "Every agent's start runs a binary the agent cannot change: claude, omp, codex and agy, which today install it under /home/agent, as pi and stub already do under /usr/local/bin.",
  "openQuestions": 1
}
```

**Why this size.** M: a root step after the agent's layers, and the four adapters' layouts read and tested.
Estimate: 63 minutes (Core's median for M), p80 229.

**What must be true.** What an agent writes during a task never picks what Porelo starts as that agent next time.

Carried on from [B738](README.md#b738), which closed the start files and `PATH`. Read in the agent repositories:

| Agent | Where its binary is installed | Owned by |
|---|---|---|
| pi | `/usr/local/bin/pi` (`as_root`) | root |
| stub | `/usr/local/bin/porelo-stub-cli` | root |
| claude | `$XDG_DATA_HOME/claude/versions/<v>`, linked from `$XDG_BIN_HOME/claude` | the agent |
| omp | `$XDG_BIN_HOME/omp` | the agent |
| codex | `$XDG_BIN_HOME/codex` and `codex-code-mode-host` (`as_agent`, `install -D`) | the agent |
| agy | `$XDG_BIN_HOME/antigravity`, with an `agy` wrapper | the agent |

For the last four, the agent can overwrite its own binary, so the next start runs what the agent wrote, whatever
path names it. `AgentDefinition.binary` is a bare name that each start looks up on `PATH`, in `buildCommand` and
`attendedCommand`.

## Plan

- After the agent's layers, a Porelo root step resolves `binary` once, at build time. It writes a root-owned
  `/usr/local/lib/porelo/agents/<name>` that execs the resolved path, and every start runs that path. No change to
  the definition or agents/api.
- What the resolved path points at is made root-owned: chowned in place, or copied into a root-owned directory. This
  includes claude's version directory and codex's sibling helper. How depends on the open question.
- First red, in `runtime`: an image whose agent installs under `.local` in the agent's home; after the build, the file the
  start runs and its target are owned by root and not writable by the agent.

## Acceptance

- For each of the six agents, the file a start runs, and what it execs, cannot be changed by the agent's user.
- An agent that updates itself in a task fails to write over it, and the next start runs the built version.

## Open questions

- Take the four installs under root in place (chown the resolved target and its directory, so an agent's
  self-update fails), or have each adapter install as root like pi? The first keeps adapters as they are; the second
  is the adapters' change and needs their repositories.
