# B815 — An Agent's Own Model List

```json
{ "$schema": "issue-1.json", "id": "B815", "title": "An Agent's Own Model List",
  "state": "Idea", "size": "M", "priority": "High", "rank": 51, "blockedBy": [],
  "covers": "An agent definition may declare its own models, a static list of id and name, and Models answers it with from 'agent' where the provider gives no list for the stored credential, so a task on an agent's own sign-in offers a model list instead of free text" }
```

**Why this size.** M: a new part of the agent definition in the agent API with its reader, `Models` answering it in
`ModelLists`, the contract's reserved `agent` parameter given its meaning, and `doc/`, with their tests.

**What must be true.** A person starting work with an agent on its own sign-in, such as Claude Code on a
subscription, picks the model from a list, never types it; the list comes from the provider where it can be asked,
and from the agent's definition where it cannot.

## Plan

- Read in porelo at 05c58a30e. Today `Models(provider, agent, fresh)` asks only the provider through
  `ModelLists`: no vault entry under the provider's name, or a stored kind the provider cannot broker, answers
  `from: "none"` with no models, and the window then offers the model as text. On a laptop where Claude Code runs on
  its subscription sign-in, Start work shows that text field. The contract's `agent` is accepted and reserved.
- **The agent definition** gains an optional `models` section: a list of `{id, name}`, the ids the agent's own model
  flag takes as they are (Claude Code's aliases `fable`, `opus`, `sonnet`, `haiku`). It is a new record beside
  `Effort` in `agents/api` (`AgentDefinition`, `AgentDefinitionReader`, `AgentDefinitionJson`), read as `efforts`
  are: an empty list, a duplicate id or a non-text value refused.
- **`Models` with `agent`**: where the provider's answer would be `none`, or the provider refuses the credential the
  agent signs in with, and the named agent declares `models`, the answer is that list with `from: "agent"`. A
  provider list that answers wins over the agent's. An unknown agent is `InvalidParameter`. The agent's list is
  served from the definition and asks nothing, so it is neither cached nor limited per minute.
- **Contract and docs**: `40-agent.varlink` (`agent` and `from` say `"agent"`), `porelo agents --verbose` prints the
  list, `agents/README.md` and `doc/` describe the section; a changelog entry under *Added*.
- **Red first**: `AgentDefinitionReader` tests for the section and its refusals; a `ModelListsTest` case where no
  credential is stored under the provider and the agent declares models answers them with `from: "agent"`, and one
  where the provider answers keeps `from: "provider"`.

## Acceptance

- With no brokerable credential for `anthropic` and the stub agent declaring two models, `Models` answers both,
  `from: "agent"`; with a list from the provider, the provider's.
- An agent definition with a malformed `models` section is refused when it is read.

## To be checked

- Whether the window's case on the laptop is "no entry under `anthropic`" or "a kind it cannot broker"; either
  ends in the agent's list once this is built.
