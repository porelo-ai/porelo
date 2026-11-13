# P09 — A Native Agent And A Provider It Does Not Know

```json
{
  "$schema": "issue-1.json",
  "id": "P09",
  "title": "A Native Agent And A Provider It Does Not Know",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 40,
  "blockedBy": [],
  "covers": "A native agent is paired with every provider today and fails inside the task: `provider.knows` in the agent API and an `UNKNOWN_TO_AGENT` refusal."
}
```

**Why this size.** one change of `porelo-agent-api`, which the agent repositories read and take, a new refusal at the start,
`porelo agents --verbose`, docs and an acceptance step.

**What must be true.** An agent that speaks `native` and is given a provider whose name it does not recognize is
refused with a reason before a task starts, naming the providers it knows.

## Plan

Read in the code on 2026-11-04, it does not work, and nothing refuses it. `ProviderDefinition.serves("native")` is true
for
every provider, so `AgentProvider.canDrive` pairs a `native` agent with any provider file, and the start goes ahead.
The agent is then told only the provider's name and the endpoint (`SetupContext`: token, credential type, workspace,
endpoint, provider, subagents): Pi's setup registers `pi.registerProvider(<name>, { baseUrl, apiKey })`, which
redirects a
provider Pi already knows and gives it nothing to go on for one it does not - no wire format, no models. So the start
succeeds and the agent answers that it does not know the provider, the failure the acceptance names.

This issue is the refusal, which is small and makes the failure honest. The reach - an agent driving a provider it
does not know - is out of scope here and will be an issue of its own (see the end of the plan).

1. **Red first:** a test in `apps/app/src/test/java/org/fuin/porelo/app/SelectedProviderTest.java` that a `native`
   agent naming the providers it
   knows is refused a provider file outside that list, with a refusal that names the list, and is given one in it.
2. **`AgentProvider`** (`agents/api`) gains `knows`, the provider names a `native` agent recognizes, read from the
   definition's `provider.knows` by `AgentDefinitionReader`. `canDrive` is unchanged for an agent naming a dialect;
   for `native` it holds only for a name in `knows`. An empty list keeps today's behaviour, so an agent that has not
   taken the new API is not broken by it.
3. **`SelectedProvider`** (`apps/app-base/src/main/java/org/fuin/porelo/app/SelectedProvider.java`) gains a refusal
   `UNKNOWN_TO_AGENT` beside `WRONG_DIALECT`, and `StartCheck` turns it into a
   refusal before anything is made, naming the agent, the provider and the providers it knows.
4. **`porelo agents --verbose`** (`AgentsCommand`, `ProviderInventory`) pairs a `native` agent only with the providers
   it knows.
5. **The agent repositories** (`porelo-pi`, `porelo-omp`) list what their agent knows in their definitions; each takes
   the new API version.
6. **Docs:** `doc/credentials.md` ("Model providers") says what `native` pairs with.
7. **Acceptance:** a provider file `example-unknown` declaring `openai`, chosen for Pi with `--provider`: refused
   before the start, naming the providers Pi knows. Red today: the start succeeds.

**Not here: the reach.** `SetupContext` carrying the resolved dialect (`openai`, `anthropic-messages`), so an agent
can register a provider it does not know with that wire format, and an agent that does so saying
`provider.knows: any`, is wanted, and is an issue of its own. The refusal built here is what `any` will lift.

## Acceptance

- A provider file with a name a `native` agent does not know, declaring a dialect it can speak, is chosen with
  `porelo task start --provider <name>`, and the start is refused before the task starts, naming the agent, the
  provider and the providers the agent knows. Seen to fail: the start succeeding, and the agent answering that it
  does not know the provider.
- A provider in the agent's list is still given to it, and an agent that lists nothing behaves as it does today.
  Seen to fail: either refused.

## Built (2026-11-13)

- `AgentProvider` (`porelo-agent-api`) carries `knows`, read from `provider.knows` by `AgentDefinitionReader` and
  carried by `AgentDefinitionJson`. A list only, and only beside `dialect: native`; a single word or a list
  beside another dialect is refused, so `any` stays free for the reach. `dialectFor` chooses `native` only for a
  provider the agent knows, so `canDrive`, `SelectedProvider.drivable`, `porelo agents --verbose` and
  `porelo providers` pair a `native` agent only with those; an empty list keeps every provider.
  `ProviderInventory` needed no change: it does not pair agents with providers.
- `SelectedProvider.Refusal.UNKNOWN_TO_AGENT`, and `StartCheck.Outcome.UNKNOWN_TO_AGENT` with its value in
  `StartOutcome` (`15-task.varlink`). The start takes it through `SelectedProvider.choose`, as `WRONG_DIALECT`,
  and exits as that does (2 on a dry run).
- Docs: `doc/credentials.md` ("Model providers") and the example definition in `agents/README.md`.
- Step 5 is the agent repositories' own: each adds `knows:` to its definition's `provider` block once it takes
  the new API version.
- Step 7 is not in the suite: every scenario that starts a task names the stub, which speaks
  `anthropic-messages`, so a scenario choosing a provider for Pi does not fit a feature file here. The refusal
  and the pairing are held by unit tests (`SelectedProviderTest`, `AgentDefinitionReaderTest`).
