# B738 — What A Task Writes Never Picks Its Next Agent

```json
{
  "$schema": "issue-1.json",
  "id": "B738",
  "title": "What A Task Writes Never Picks Its Next Agent",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "Nothing an agent writes in its task - a binary in $XDG_BIN_HOME, a shell start file, a variable - changes which agent binary or which environment a later start of an agent in that task runs; the start runs the root-owned wrapper by its own path with an environment Porelo sets.",
  "openQuestions": 0
}
```

**Why this size.** M: the image's `PATH`, how every agent start and resume in the container is executed (the
unattended run and the interactive attach through a login shell), the environment it passes, tests in `runtime` and
`apps/app`, and the image's documentation, for every agent alike. Estimate: 63 minutes (Core's median for M), p80
229.

**What must be true.** Whatever an agent writes in its task, a later start of an agent in that task runs the agent
binary Porelo installed, through its root-owned wrapper, with the environment Porelo gives it: never a binary, a
start file or a variable the agent left behind.

Found in a reading for the pi agent, true for every agent. Read at the stack's head: the image sets
`PATH=$XDG_BIN_HOME:/usr/local/bin:/usr/bin:/bin` (`Containerfile`, from `TaskVariables`), so the agent's own
`$XDG_BIN_HOME`, which the agent owns, comes before the root-owned wrapper. The interactive attach runs its first
command through `bash -lc` (`Podman`), which reads the agent's own shell start files. So a `pi`, `claude`, `codex` or
other binary the agent drops into `$XDG_BIN_HOME`, or a variable such as `PI_PACKAGE_DIR` set in a start file, is what
the next start runs or runs with. This is Porelo's to close, not an adapter's: an adapter may keep its own directory.

## Plan

- Every agent start and resume in the container runs the wrapper by its absolute, root-owned path, never by a name
  looked up on `PATH`.
- The start's environment is the one Porelo sets: the shell it runs through reads no start file the agent owns
  (no login shell, no `BASH_ENV`, no `ENV`), and the variables an agent names are Porelo's alone.
- `PATH` in the image puts the root-owned directories first; the agent's `$XDG_BIN_HOME` stays on it, last, for what
  the agent installs for its own use.
- First red, in `runtime` and `apps/app`: a task whose agent left a `pi` (or the agent's own name) in `$XDG_BIN_HOME`
  and a variable in `.bashrc` and `.profile`; the next start's command line and environment still name the
  wrapper's path and Porelo's values.

## Acceptance

- A binary of the agent's name in `$XDG_BIN_HOME` and a start file setting the agent's variables change nothing in
  what the next start, unattended or attached, runs.
- What the agent installs for its own use in `$XDG_BIN_HOME` is still found by its own shell.
