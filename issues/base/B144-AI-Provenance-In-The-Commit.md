# B144 — AI Provenance In The Commit

**Status:** Analyzed; blocked by sokar [B148](README.md#b148).
**Size:** L - a new merge path for every approve in `gate`, a reader of the broker's record, the daemon's approve,
docs and an acceptance step; and the merge against a linear history is a decision first.

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

- **Where the models come from:** the broker's per-task record ([B148](README.md#b148)): for each request the model asked for and the model
  the provider answered with, and the token counts. Approve reads the period since the task's previous approve.
- **Trailers:**

      AI-Model: anthropic/claude-sonnet-5-5 (412 requests), z-ai/glm-5.3-flash (37 requests)
      AI-Agent: claude
      AI-Provider: openrouter
      Sokar-Task: payments/backend-fix

  The names are to be decided. Attribution is per period, not per line or per commit.
- **Where they go:** on a merge commit the host makes at approve, with the reviewed commit untouched as its second parent,
  so what lands is what was reviewed ([B136](README.md#b136)). A signed approve makes that merge already and signs the trailers with it;
  a plain approve makes the same merge without `-S`. No commit of the agent's is rewritten, and git notes are not used:
  they are not pushed by default and not signed.
- **Read on the host only:** from the broker's record and the task's record, never from the agent or its workspace.
- **The online class's pass-on** goes to the task's own branch as the agent pushes, with no approve and no merge; it
  carries no trailers, and the documentation says so.

## Plan

**Today.** `GitGate.approve` (`gate/src/main/java/org/fuin/sokar/gate/GitGate.java`) pushes the reviewed commit as
it is. `GitGate.approveSigned` merges in a scratch clone with `git merge --no-ff -S -m "Merge <name>, reviewed and
signed"`, checks the merge carries a signature, and pushes it. `GateApproveCommand`, `ApproveCommand` and the
daemon's `Approve` (`GateMethods`, `35-gate.varlink`) call them. The task's agent and provider are in `TaskProfile`
(`agent`, `provider`); `GitGate.passOn` is the online pass-on.

**Steps:**

1. After [B148](README.md#b148): `Provenance` in `apps/app-gate` reads the task's usage record (kept after a removal, renamed as the
   clearance journal is) from the position recorded at the task's previous approve, and sums requests per answered
   model. A task with no record (started before [B148](README.md#b148)) gets `AI-Model: not recorded`.
2. One merge path in `GitGate` for both approves: scratch clone, `git merge --no-ff [-S]`, the message with the
   trailers. The position in the usage record is written only after the push succeeded, so a failed approve counts
   its requests again next time.
3. The trailer names in one place in `gate`, stated in `doc/how-it-works.md`; a documents test holds the two
   together. The page also says the online pass-on carries none.
4. Red first: a `GitGate` test with a stand-in usage record of two models - an approve's merge on the bare upstream
   carries `AI-Model:` naming both with their counts, and `HEAD^2` is the reviewed commit; a second approve names
   only the requests after the first.
5. Acceptance: a stand-in provider answering with two models in the acceptance kit, then an approve.

## Acceptance

- Seen to fail first, then green: two models answered for a task (a stand-in provider), then an approve - the upstream
  branch's merge carries `AI-Model:` naming both with their counts, and its second parent is the reviewed commit.
- A second approve of the same task names only the models of the requests since the first.
- `doc/how-it-works.md` says what the trailers hold and where they come from.

## Open questions

- **A merge or a linear history:** a branch protected for linear history refuses the merge. Whether a project chooses
  between a merge with provenance and a fast-forward without, and what the default is (the operator's).

## To be checked

- Whether a squash or a rebase downstream keeps them: a squash only if its message does, a rebase drops merge commits;
  whether the page only says so or Sokar offers more (Agent Core's).
- The trailer names (Agent Core's).
