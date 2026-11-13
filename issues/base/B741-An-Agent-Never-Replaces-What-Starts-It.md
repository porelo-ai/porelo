# B741 — An Agent Never Replaces What Starts It

```json
{
  "$schema": "issue-1.json",
  "id": "B741",
  "title": "An Agent Never Replaces What Starts It",
  "state": "Analyzed",
  "size": "S",
  "priority": "High",
  "rank": 50,
  "blockedBy": [],
  "covers": "Every agent's start runs its binary by an absolute, root-owned path and refuses one the agent could change; each adapter installs its binary as root, as pi does.",
  "openQuestions": 0
}
```

**Why this size.** S, Porelo's part only: the binary's path fixed at build and checked at each start. The adapters'
part is theirs. Estimate: 45 minutes (Core's median for S).

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

The operator decided on 2026-11-13: each adapter installs its agent's binary as root, as pi does, and Porelo does not
chown an agent's install. What stays Porelo's:

- **At build:** after the agent's layers, Porelo resolves `AgentDefinition.binary` on root's `PATH` once. It records
  the absolute path and its resolved target in the image, as a label beside the recipe's.
- **At each start, unattended or attached:** Porelo runs that absolute path, never a bare name. It refuses the start
  when the path, its resolved target, or any directory above either can be written by the agent's user. The refusal
  names the agent and the path, and says the adapter must install it as root.
- First red, in `runtime` and `apps/app`: an image whose agent's binary resolves to `$XDG_BIN_HOME/<name>`
  is refused at start, with that message. A root-owned `/usr/local/bin/pi` starts by its absolute path.

**What each adapter must deliver** (Smith files these in the adapters' repositories):

| Adapter | Today | Must deliver |
|---|---|---|
| claude-code | `$XDG_DATA_HOME/claude/versions/<v>`, linked from `$XDG_BIN_HOME/claude` | the version and its link under a root-owned prefix (e.g. `/usr/local/lib/claude`, `/usr/local/bin/claude`), written in `as_root`; self-update off or failing harmlessly |
| omp | `$XDG_BIN_HOME/omp` | `omp` in `/usr/local/bin`, written in `as_root` |
| codex | `$XDG_BIN_HOME/codex` and `codex-code-mode-host`, `install -D` in `as_agent` | both in `/usr/local/bin` (or one root-owned directory), installed in `as_root`; the helper found by codex there |
| agy | `$XDG_BIN_HOME/antigravity` and an `agy` wrapper | both root-owned under `/usr/local`, written in `as_root` |

Until an adapter delivers, its agent's start is refused with the message above, so the release that ships this waits
for the four adapters or carries them.

## Acceptance

- For each of the six agents, the file a start runs, and what it execs, cannot be changed by the agent's user.
- A start whose agent binary the agent's user could change is refused, naming the path.
- An agent that updates itself in a task fails to write over it, and the next start runs the built version.

## Answered

- Each adapter installs its agent's binary as root, as pi does; Porelo does not chown an agent's install. Porelo's
  part is the absolute, root-owned path and the refusal (the operator, 2026-11-13).
