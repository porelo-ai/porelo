# P10 — A Native Agent Reaching A Provider It Does Not Know

**Status:** Analyzed; blocked by [P09](README.md#p09).
**Size:** M - one additive change of `sokar-agent-api` (a field of `SetupContext`, a key of the definition's
`provider` block) that the agent repositories take, the dialect resolved once in `agents/api` and handed on by
`TaskLaunch`, `sokar agents --verbose`, docs; the agents' own registration is an issue in each agent repository.

**What must be true.** An agent that speaks `native` is told the resolved dialect (`openai`, `anthropic-messages`) in
its `SetupContext`, so it can register a provider whose name it does not recognise with that wire format and reach it
through the broker. An agent that can do this says so in its definition, which lifts the refusal
[P09](README.md#p09) gives before the start, for a provider serving a dialect it can register.

## Plan

Read in today's code. A `native` agent is handed the endpoint with the provider's first dialect's path already on it
(`ProviderRoute.of` → `ProviderDefinition.pathFor("native")`), but never the dialect's name: `SetupContext` is token,
credential type, workspace, endpoint and provider, and `ProviderRoute` keeps only the path. Pi's setup
(`PiRoutingExtension`) and omp's (`OmpModelsFile`) can therefore only redirect a provider they already know. Both
agents can register an unknown one if told its wire format - Pi's `registerProvider(name, { baseUrl, apiKey, api,
models })`, omp's `providers.<name>` with `api` and `models` or a model-list discovery in `models.yml` - so the gap is
the name of the dialect, and a way for the agent to say it can use it. [P09](README.md#p09) adds `provider.knows` and
the `UNKNOWN_TO_AGENT` refusal; this lifts that refusal.

The definition keeps [P09](README.md#p09)'s `knows` list and gains `provider.registers`, the dialects the agent can
register a provider it does not know with (see *Open questions*). The dialect is resolved in one place, in the order:
a provider named in `knows` gets its first dialect, as today; any other gets the first of its own dialects that the
agent lists in `registers`, in the provider's order; none of them is [P09](README.md#p09)'s refusal, now also naming
the dialects the agent registers.

1. **Red first:** in `apps/app/.../SelectedProviderTest.java`, a `native` agent knowing `openrouter` and registering
   `openai` is given a provider file `example-unknown` declaring `anthropic-messages` then `openai`: chosen, with the
   resolved dialect `openai` and `openai`'s path on the route; the same agent registering nothing is refused as
   `UNKNOWN_TO_AGENT`; a provider in `knows` still resolves to its first dialect. In `AgentProtocolTest`,
   `everyPartOfTheSetupContextSurvivesTheWire` and `theClientSendsEveryFieldOfTheSetupContext` carry the dialect.
2. **`AgentProvider`** (`agents/api`) gains `registers` (a list, empty by default) and `dialectFor(ProviderDefinition)`,
   the resolved dialect or `null`; `canDrive` becomes `dialectFor(...) != null`, so `drivable`, `AgentsCommand`,
   `ProvidersCommand` and `StartCheck` follow without their own rule. For an agent naming a fixed dialect it is that
   dialect when served, as today. Read by `AgentDefinitionReader` (`provider.registers`), written and read by
   `AgentDefinitionJson` so it survives `Describe`.
3. **`ProviderRoute`** gains `dialect`, the resolved name, set by `ProviderRoute.of` from `dialectFor` and used for
   `pathFor`, so the path and the name handed over can never disagree.
4. **`SetupContext`** gains `dialect` (empty when the task is not brokered); `parameters()` sends it, `AgentServer`
   reads it with `text(...)`, and the five-argument constructor stays, so an agent's tests compile unchanged. An agent
   on today's API ignores the extra parameter and an agent on the new one hears `""` from an older Sokar, so
   `AgentProtocol.VERSION` stays 3, by its own rule (an added optional field does not qualify).
5. **`TaskLaunch.placeAgentFiles`** passes `selection.route().dialect()`.
6. **`sokar agents --verbose`** shows a pair reached by registering as such (the provider, `registered as openai`),
   so a person sees which pairs rest on the agent's catalogue and which on the wire format alone.
7. **Docs:** `agents/README.md`'s `provider` block shows `knows` and `registers`, and says what the setup is told;
   `doc/credentials.md` ("Model providers") says how a `native` agent is paired with a provider it does not know.
8. **The agent repositories** each get an issue: `sokar-pi` and `sokar-omp` register a provider outside their own
   catalogue with the dialect's wire format and the model the run names, list `registers` in their definitions, and
   prove it with a live scenario. `sokar-claude-code` needs none: it names `anthropic-messages`, not `native`, and
   already reaches any provider serving that dialect.

## Acceptance

- In `sokar`: a `native` agent registering `openai` is paired, in `sokar agents --verbose` and at the start, with a
  provider file `example-unknown` it does not know that serves `openai`, and its setup is told `dialect: openai`. Seen
  to fail: the pair refused, or the setup told an empty dialect or the provider's first one when the agent cannot
  register it.
- A provider the agent knows is still given its first dialect, and an agent registering nothing is still refused as
  [P09](README.md#p09) refuses it. Seen to fail: either changing.
- Live, in the Pi repository's scenario: a copy of the OpenRouter provider file under the name `example-unknown`,
  chosen with `sokar task start --provider example-unknown --model <id>`, answers a prompt through the broker with
  the credential stored under `example-unknown`. Seen to fail: Pi answering that it does not know the provider, or
  the request leaving the broker for anything but OpenRouter's host.

## Open questions

- **How the agent says it can register an unknown provider.** The issue as written says `provider.knows: any`, but
  `any` tells Sokar neither which providers the agent knows nor which wire formats it can register: a provider whose
  first dialect the agent cannot map (Google's own `google-generative-ai`, or any name a provider file invents) would
  start and fail inside the task - the failure [P09](README.md#p09) exists to remove - and a provider the agent does
  know could no longer be told apart. *Proposal:* keep [P09](README.md#p09)'s `knows` list and add
  `provider.registers: [openai, anthropic-messages]`; the refusal is lifted for a provider serving one of those, and
  the resolved dialect is the first of the provider's dialects in that list. Alternative: `knows: any` as written,
  the provider's first dialect always, and a dialect the agent cannot map failing in its setup.

## To be checked

- Which of Pi's and omp's wire formats each Sokar dialect maps to: `anthropic-messages` to `anthropic-messages`;
  `openai` to `openai-completions` or `openai-responses` - omp under `openrouter` posts to `/responses`, while a
  local or compatible server ([B149](../base/README.md#b149), [P08](README.md#p08)) may serve only chat completions.
  Measured by each agent's repository.
- Whether the agents find an unknown provider's models through the broker (`<endpoint>/models`, as Pi's
  `refreshModels` or omp's `openai-models-list` discovery would ask), or must be told the model the run names. If the
  latter, `SetupContext` also carries `model` (empty for a run that names none), in this same change, so the API moves
  once.
