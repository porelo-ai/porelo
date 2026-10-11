# P09 — A Native Agent And A Provider It Does Not Know

**Status:** Analyzed.
**Size:** M - one change of `sokar-agent-api`, which the agent repositories read and take, a new refusal at the start,
`sokar agents --verbose`, docs and an acceptance step.

**What must be true.** An agent that speaks `native` either reaches a provider whose name it does not recognize,
through the first dialect that provider declares, or is refused with a reason before a task starts.

## Plan

Read in today's code, it does not work, and nothing refuses it. `ProviderDefinition.serves("native")` is true for
every provider, so `AgentProvider.canDrive` pairs a `native` agent with any provider file, and the start goes ahead.
The agent is then told only the provider's name and the endpoint (`SetupContext`: token, credential type, workspace,
endpoint, provider): Pi's setup registers `pi.registerProvider(<name>, { baseUrl, apiKey })`, which redirects a
provider Pi already knows and gives it nothing to go on for one it does not - no wire format, no models. So the start
succeeds and the agent answers that it does not know the provider, the failure the acceptance names.

The refusal first, because it is small and makes the failure honest; the reach second, as a step of its own.

1. **Red first:** a test in `apps/app/.../SelectedProviderTest.java` that a `native` agent naming the providers it
   knows is refused a provider file outside that list, with a refusal that names the list, and is given one in it.
2. **`AgentProvider`** (`agents/api`) gains `knows`, the provider names a `native` agent recognizes, read from the
   definition's `provider.knows` by `AgentDefinitionReader`. `canDrive` is unchanged for an agent naming a dialect;
   for `native` it holds only for a name in `knows`. An empty list keeps today's behaviour, so an agent that has not
   taken the new API is not broken by it.
3. **`SelectedProvider`** gains a refusal `UNKNOWN_TO_AGENT` beside `WRONG_DIALECT`, and `StartCheck` turns it into a
   refusal before anything is made, naming the agent, the provider and the providers it knows.
4. **`sokar agents --verbose`** (`AgentsCommand`, `ProviderInventory`) pairs a `native` agent only with the providers
   it knows.
5. **The agent repositories** (`sokar-pi`, `sokar-omp`) list what their agent knows in their definitions; each takes
   the new API version.
6. **The reach, a later step:** `SetupContext` also carries the resolved dialect (`openai`, `anthropic-messages`),
   so an agent can register a provider it does not know with that wire format; an agent that does so says
   `provider.knows: any`.
7. **Docs:** `doc/credentials.md` ("Model providers") says what `native` pairs with.
8. **Acceptance:** a provider file `example-unknown` declaring `openai`, chosen for Pi with `--provider`: refused
   before the start, naming the providers Pi knows. Red today: the start succeeds.

## Acceptance

- A provider file with a name no agent knows, declaring a dialect a `native` agent can speak, is chosen with
  `sokar task start --provider <name>`, and the task authenticates through the broker. Seen to fail: the agent
  ignoring the redirected endpoint, or the start succeeding and the agent answering that it does not know the
  provider.
- Until step 6 is built: the same start is refused before the task starts, with the reason. Seen to fail: the start
  succeeding.

## Open questions

- Whether step 6, the reach, is wanted at all, or the refusal is the end of it (the operator's).
