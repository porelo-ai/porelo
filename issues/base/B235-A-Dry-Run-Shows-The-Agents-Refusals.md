# B235 — A Dry Run Shows The Agent's Refusals

**Status:** Idea.

**What must be true.** `porelo task start --dry-run` names a host the task's agent refuses as refused, never as
reachable, so what the dry run says is what the running task enforces.

## Why

Measured on `0.5.1~snapshot.307`: `task start --dry-run --agent claude` lists `raw.githubusercontent.com` (from the
set `git-hosting`) and `downloads.claude.ai` (from the project) as reachable, exactly as with `--agent stub`, while the
running task with that agent refuses both: an agent's refused domains win over the project's, and a lookup there
answers NXDOMAIN. Someone reading the dry run to plan a build learns the opposite of what happens.
