# B301 — An Unattended Task For An Issue Takes Its Phase From It

**Status:** Idea.

**What must be true.** An unattended task - one without a shell for a person - is started for one issue, and the
phase it works in follows from that issue, for example from its state, so the person names the issue and nothing
else.

**It adds a way and takes none away.** `porelo task start ... -P/--prompt "<free text>"`, for any repository and with
no issue and no phase, stays exactly as it is. "An unattended task works on one issue, in the phase that issue is in"
is how porelo-project works, not a rule porelo enforces on every project.

## Why

The operator, 2026-10-27, settling porelo-frontend [F148](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f148):
an attended agent is the long-running agent of its repository and gets no issue and no phase; an unattended one is
always started for one issue, and its phase follows from that issue. Read in porelo at 0b02c1b7, nothing does that:
a task takes the values its project declares, `--set`, `--set-file` and `--set-env`, each checked and fixed for its
life (finished [B189](README.md#b189)), so an issue and a phase reach it only as two values a person or a script typed.
porelo knows a repository's issue prefixes (`repositories.<repository>.issues`), not an issue or its state.

## To be checked

- Where the issue's state is read: the repository's `issues/` at the mirror's commit the task starts from, or the
  forge's issue tracker, or both; and what a phase is, a name the project declares or a fixed set.
- How it relates to [B299](README.md#b299) (the declared parameters an interface reads) and to the project
  parameters a project names `issue` and `phase` today, if any do.
