# B630 — A Task's Record Splits Its Work By Sub-Agent

```json
{
  "$schema": "issue-1.json",
  "id": "B630",
  "title": "A Task's Record Splits Its Work By Sub-Agent",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [],
  "covers": "A task's record names each sub-agent's own span with its issue and parent, counts tokens per sub-agent, takes an issue id with a hyphen, and keeps several issue and phase pairs given at the start."
}
```

**Why this size.** L: the tool-call entries and the task record change shape (`porelo task records` is a contract a
planner outside porelo reads), Tasks1 `Start` and `--work` take a list, and tokens per sub-agent need the agent API
to carry a sub-agent's usage, which the agents' own repositories then fill.

Filed 2026-11-12 in `porelo-project` ST320, Backlog by the operator's decision of 2026-11-12.

**What must be true.** A planner reading `porelo task records` can give each issue the exact time and tokens its
sub-agents spent, instead of splitting a task's time evenly among its issues: every sub-agent's own span says which
issue it worked and which call it ran under, tokens are counted per sub-agent, an issue id such as `ISSUE-1` names an
issue as `B630` does, and a task started for several issues and phases keeps each of them.

How it is today, read in the code:

- **a. A sub-agent's span names no issue, and no call names its parent.** `ToolCalls.issue(id)` walks a call's
  parents to the nearest sub-agent named like an issue, so a call inside a sub-agent carries that issue; the
  sub-agent call itself starts the walk at its own parent and so carries its parent's issue, or none, never its own
  name. An entry has no `id` and no `parent`, so a reader cannot rebuild which call ran under which sub-agent.
- **b. Tokens are counted per task only.** `TaskWorkRecords` writes `totals` from the broker's usage of the whole
  container; nothing says which sub-agent or call a request was made for.
- **c. An id with a hyphen never names an issue.** `ToolCalls.ISSUE` is `[A-Z]{1,4}[0-9]{1,6}`, while `TaskWork`'s
  own example of an issue is `ISSUE-1`.
- **d. One `{issue, phase}` per start.** `TaskWork` is one pair; Tasks1 `Start` carries `work: ?(issue: string,
  phase: string)`, `porelo task run --work <issue>:<phase>` takes one, and the record writes one `work` object.

## Plan

Read in today's code: `apps/app-task` `ToolCalls`, `TaskWork`, `TaskWorkRecords`, `TaskRecordsCommand`,
`TaskRunCommand` (`--work`), `TaskLaunch`; `daemon` `PoreloDaemon` (`TaskWork.of`), `TaskMethods`, `McpTaskSockets`;
`wire` `org.fuin.porelo.Tasks1/15-task.varlink`; `agents/api` `ToolEvent`.

1. **c, the issue id.** One pattern for an issue id, shared by `ToolCalls` and `TaskWork`, taking an uppercase prefix,
   an optional hyphen and a number (`B630`, `ISSUE-1`), bounded as today. Red first: `ToolCallsTest`, a call under a
   sub-agent named `ISSUE-1` carries `ISSUE-1`.
2. **a, spans and parents.** Each entry gains `id` (the agent's call id) and `parent` (the id of the sub-agent call
   it ran under, or null); a sub-agent call's own `issue` is its own name when that is an issue id, else its
   parent's as today. Red first: a sub-agent named `B1` carries `B1` itself, and a call under it names it as parent.
3. **d, several pairs.** `work` becomes a list of `{issue, phase}` in Tasks1 `Start`, in the record and in
   `TaskRecordsCommand`'s output; `--work` may be given more than once; an empty list is no work. Each pair is
   checked as `TaskWork.refusal` checks one today, duplicates refused, at most a bounded number. The wire change is
   additive where varlink allows it, and the window and the planner are told its shape in the channel before it
   lands. Red first: `TaskRunCommandTest`, two `--work` values reach the record as two pairs; a third identical one
   is refused.
4. **b, tokens per sub-agent.** `ToolEvent` gains an optional usage, per kind of token, that an agent reads from its
   stream for a sub-agent call; `ToolCalls` sums it per sub-agent call into that entry's `totals`, and the task's
   own `totals` stay the broker's count. An agent that cannot say leaves it null, never zero. The agent API change
   is announced in the channel; the stub fills it so the acceptance suite sees it, and the shipped agents follow in
   their own repositories. Red first: `ToolCallsTest`, two usages of one sub-agent add up on its entry and a call
   without one stays null.
5. `doc/` on the record's fields and the changelog under `[Unreleased]`.

All tests are unit tests over fakes; the stub's usage is seen once in the acceptance suite.

## Acceptance

- The tests above, each seen red first; the full build and the hand-over checks green.
- `porelo task records --json` for a stub task with two sub-agents named by issues, one with a hyphen, and two
  `--work` pairs shows each sub-agent's own issue, its parent, its tokens, and both pairs.

## To be checked

- Whether each shipped agent's stream reports usage per sub-agent at all, and in which form, read on one real stream
  per agent with that agent's repository; where none does, step 4 builds only the API and the stub.
- Whether Tasks1 `Start`'s `work` can grow into a list compatibly, or needs a second field beside the old one for a
  while.
