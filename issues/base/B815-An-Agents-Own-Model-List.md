# B815 — An Agent's Own Model List

```json
{ "$schema": "issue-1.json", "id": "B815", "title": "An Agent's Own Model List",
  "state": "Review", "size": "M", "priority": "High", "rank": 51, "blockedBy": [],
  "covers": "An agent definition may declare its own models per provider, a static list of id and name, and Models answers it with from 'agent' where the provider gives no list for the stored credential, so a task on an agent's own sign-in offers a model list instead of free text" }
```

**Why this size.** M: one contract change (`Models` answering `from: "agent"`, the reserved `agent` given its
meaning), a new part of the agent definition in the agent API with its reader, `ModelLists` and `AgentMethods`, and
`doc/` and `agents/README.md`, with their tests; no other repository's code changes with it. Estimate: 79 minutes
(Core's median for M).

**What must be true.** A person starting work with an agent on its own sign-in, such as Claude Code on a
subscription, picks the model from a list, never types it; the list comes from the provider where it can be asked,
and from the agent's definition where it cannot.

## Plan

Read in porelo at ea5a5b7ac.

- **Today.** `AgentMethods.models` takes `provider` and `fresh`, ignores `agent`, and calls
  `ModelLists.list(provider, credentials, fresh)`. That answers `from: "none"` with no models where the provider file
  names no `models` section, the vault holds no entry under the provider's name, or the stored kind cannot be
  brokered; a refused or unreachable ask is `ModelsFailed`. Claude Code on a subscription hands its sign-in to the
  task as `CLAUDE_CODE_OAUTH_TOKEN`, so nothing is stored under `anthropic` and Start work shows a text field.
- **The definition's `models`, keyed by provider.** An optional section `models: {<provider>: [{id, name}]}` in the
  agent definition (`AgentDefinition`, `AgentDefinitionReader`, `AgentDefinitionJson` in `agents/api`), read as
  `effort` is: a new record `AgentModels` (`Map<String, List<Model>>`, order kept). Keyed by provider because an id
  such as `opus` means a model only at the provider the agent signs in to; an agent driving another provider must not
  offer it there. Refused when read: a key that is no provider name, an empty list, an empty or duplicate `id`, a
  `name` or `id` that is not text, an unknown member. `name` is the person's text, such as "Opus 5.5", as the agent's
  own model menu shows it.
- **`Models` with `agent`.** `AgentMethods.models` resolves `agent` among this machine's agents (unknown is
  `InvalidParameter`). Where the provider's answer is `none`, or the ask failed, and the agent declares models for
  that provider, the answer is that list with `from: "agent"` and `label` its `name`; `efforts` absent, so a client
  offers the agent's efforts. A list the provider gives wins. The agent's list asks nothing and opens no vault, so it
  is neither kept nor limited per minute; a shut vault still answers it.
- **Contract and docs.** `40-agent.varlink`: `agent` and `from` say `"agent"`, and the comment that it is reserved
  goes. `porelo agents --verbose` prints the list per provider. `agents/README.md` names the section;
  `doc/` where `Models` is described. Changelog entry under *Added*.
- **The stub** declares one provider's two models, so the acceptance kit can read them through `porelo agents
  --verbose`.
- **Red first.** In `agents/api`: a definition with `models` reads in order, and each refusal above. In `daemon`
  (`AgentMethods`): no credential stored under the provider and the agent declaring models answers them with
  `from: "agent"`; a failed ask answers them too; a provider list keeps `from: "provider"`; another provider's call
  with the same agent answers `none`; an unknown agent is `InvalidParameter`.

## Acceptance

- With no brokerable credential for a provider and the agent declaring two models for it, `Models` answers both with
  `from: "agent"`; with a list from the provider, the provider's; for another provider, `none`.
- An agent definition with a malformed `models` section is refused when it is read, naming the member.

## As built

- `agents/api`: `AgentModels` (`providers()`: a provider's name to its `Model(id, name)` list, order kept), read by
  `AgentDefinitionReader.models` from a file and from an agent's own description alike, and written by
  `AgentDefinitionJson` only where an agent declares models. Refused when read: a section that is no mapping or
  names no provider, a key that is no provider name, an empty list or one of more than 100, an unknown member, an
  `id` or `name` that is not text, blank, longer than 200 (`id`) or 100 (`name`) characters, or holds a control,
  format or line character, and a duplicate `id`.
- `AgentMethods.models`: `agent` resolved among the installed agents, unknown is `InvalidParameter`; where
  `ModelLists.list` answers `none` or throws `Failed` (a shut vault included) and the agent declares models for that
  provider, the answer is that list with `from: "agent"`, `label` its `name`, no `efforts`, neither kept nor limited
  per minute. A provider's list wins, even an empty one; another provider and an undeclared provider are as before.
- `porelo agents --verbose` prints the lists per provider; the stub declares two for one provider; the contract text
  of `Models` and `Model`, `agents/README.md`, `doc/commands.md` and `doc/credentials.md` say so; changelog entry.
- Built by Codex CLI (gpt-6.1-sol, xhigh); the lead ran the tests. Red first: `AgentModelsTest` 21 of 21 red before
  the reader existed; `ModelsMethodTest` 7 of 14 red before the fallback (`from` was `none`, a shut vault was
  `ModelsFailed`, an unknown agent was not `InvalidParameter`).
- Reading (Antigravity CLI, gemini-4-argon-high): nothing grave; the vault is never unlocked or a credential shown,
  a task's MCP caller cannot ask fresh, an undeclared provider stays refused, and the agent's list never enters the
  provider's kept list or its minute limit. Fixed, each case seen red first (9 red): `models: {}` was taken as none,
  and an `id` or `name` could carry control or format characters, be blank, or be of any length or count. Left as
  [B817](README.md#b817): a broken installed agent is answered `InvalidParameter`, two contract sentences, three
  test gaps.
- Tests on the lead's run: `AgentModelsTest` 35, `ModelsMethodTest` 14, `AgentIsolationTest`, the agents API and the
  stub's tests, the contract tests, 0 failures.
- Handed over locally to the adapters' owner for Claude Code's definition before the push.
