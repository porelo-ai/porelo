# B144 — AI Provenance In The Commit

**Status:** open. Needs B148.

**What must be true.** Approved work carries, in a commit the host makes at approve, which models made it: every model
the task's provider was asked for and answered with since that task's previous approve, with their request counts, as
the broker recorded them - never what the agent claims. Beside them the agent, the provider and the task.

## Why

Guidance asks that AI-made work be labelled, and the commit is where a reader of the history looks. A model named at the
launch (`--model`) misses one switched inside the agent during its work; the broker sees every request, whatever the
agent was told or tells.

**Guideline points it answers:** AISVS AC.9/AC.10; labelling per BSI catalogue 2.6, Myni Gmeind §5, the federal LLM
guidance sheet. From a review of security guidelines, 2026-10-09.

## The shape

- **Where the models come from:** the broker's per-task record (B148): for each request the model asked for and the model
  the provider answered with, and the token counts. Approve reads the period since the task's previous approve.
- **Trailers:**

      AI-Model: anthropic/claude-sonnet-5-5 (412 requests), z-ai/glm-5.3-flash (37 requests)
      AI-Agent: claude
      AI-Provider: openrouter
      Sokar-Task: payments/backend-fix

  The names are to be decided. Attribution is per period, not per line or per commit.
- **Where they go:** on a merge commit the host makes at approve, with the reviewed commit untouched as its second parent,
  so what lands is what was reviewed (B136). A signed approve makes that merge already and signs the trailers with it;
  a plain approve makes the same merge without `-S`. No commit of the agent's is rewritten, and git notes are not used:
  they are not pushed by default and not signed.
- **Read on the host only:** from the broker's record and the task's record, never from the agent or its workspace.
- **The online class's pass-on** goes to the task's own branch as the agent pushes, with no approve and no merge; it
  carries no trailers, and the documentation says so.

## Acceptance

- Seen to fail first, then green: two models answered for a task (a stand-in provider), then an approve - the upstream
  branch's merge carries `AI-Model:` naming both with their counts, and its second parent is the reviewed commit.
- A second approve of the same task names only the models of the requests since the first.
- `doc/how-it-works.md` says what the trailers hold and where they come from.

## To be checked

- **A merge or a linear history:** a branch protected for linear history refuses the merge. Whether a project chooses
  between a merge with provenance and a fast-forward without, and what the default is.
- Whether a squash or a rebase downstream keeps them: a squash only if its message does, a rebase drops merge commits.
- A task approved after it was removed: its broker record must outlive it, or the trailer says "not recorded".
