# B748 — A Refused Repository Setting Names The Start Command

```json
{
  "$schema": "issue-1.json",
  "id": "B748",
  "title": "A Refused Repository Setting Names The Start Command",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 73,
  "blockedBy": [],
  "covers": "A start refused without a terminal because a repository's porelo.yml asks for settings not yet allowed names `porelo task start` at a terminal as the next step.",
  "openQuestions": 0
}
```

**Why this size.** XS: two sentences in one class and their tests. Estimate: under 45 minutes (below Core's median
for S).

**What must be true.** A person whose start was refused for a repository's own settings is told the exact command to
run at a terminal to be asked.

This comes from [B470](README.md#b470), whose words promise that the refusal without a terminal names `porelo task
start` at a terminal. It was found while playing [B682](README.md#b682) on a test VM (2026-11-13). A checkout whose
`porelo.yml` holds `egress: {domains: ["example.org"]}` was started with `--no-input` and no terminal:

```
porelo: the porelo.yml of st30-…-asks asks for 1 setting this machine has not allowed: + egress.domains[example.org] = example.org. Start it in a terminal to be asked, or allow them in the window
porelo: nothing was created
```

The exit code was 64, nothing was made, and the setting was named, but `porelo task start` was not.

## Plan

- `RepositorySettingsText.notAllowed` and `imageNotAllowed` (`apps/app-model`) end with "Start it in a terminal to be
  asked". Make that "Run 'porelo task start' at a terminal to be asked, or allow them in the window". The caller
  knows the task's name and repository and may give the whole command line.
- First red, in `apps/app`: `RepositoryOwnSettingsTest` (or the start's test of a refusal without a terminal)
  expects `porelo task start` and "terminal" in the refusal.

## Acceptance

- A refusal without a terminal for a repository's settings names `porelo task start` at a terminal and still names
  each setting, and nothing is made.
