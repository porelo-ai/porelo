# B177 — A Cost Ceiling Where The Provider Reports Cost

**Status:** Analyzed; blocked by sokar [B138](README.md#b138).
**Size:** M - a field in the provider file and `ProviderDefinition` (an addition to the agent API), the cost read
beside [B148](README.md#b148)'s token count, `limits.cost` and its refusal beside [B138](README.md#b138)'s, and docs;
no varlink change beyond what [B148](README.md#b148) adds.

**What must be true.** A project may cap a task's spending in US dollars, beside the token ceiling of
[B138](README.md#b138), for providers that report the cost of each answer themselves - a router service that sells
several providers' models, for example. Nothing is computed from a price list on the host. A task whose providers do
not all report a cost is refused the ceiling at its start, never given one that silently counts nothing.

## Why

The decision "A task's spending is capped in tokens, not in money" in `doc/decisions.md` keeps money out of the token
ceiling because a price list on the host goes stale, and leaves a money ceiling for providers that report each
answer's cost. A token ceiling bounds use; a person budgets in money, and the same number of tokens costs a hundred
times more on one model than another.

**Which providers report a cost**, read in their public API documentation on 2026-10-11:

- **`openrouter`: yes**, in every answer without being asked, as `usage.cost` in credits; streamed, in the last event
  before the end.
- **xAI ([P07](../providers/README.md#p07)): yes**, as `usage.cost_in_usd_ticks`, 10^10 ticks to the dollar; in a
  streamed openai-dialect answer only in the last chunk, and only when the request asks for usage
  (`stream_options.include_usage`).
- **`anthropic`, `github-copilot`, OpenAI ([P05](../providers/README.md#p05)), Google
  ([P06](../providers/README.md#p06)), Zhipu ([P08](../providers/README.md#p08)): no**, token counts only.

## Plan

Built on [B138](README.md#b138), which is built on [B148](README.md#b148); neither exists in the code yet, so the
names below are theirs. Read in today's code: a provider is data (`providers/*.yaml`), read by
`ProviderDefinitionReader` into `ProviderDefinition` (`agents/api`), selected into a `Destination` (`app-base`); the
start (`CredentialWiring`) passes each destination to `sokar vault serve` as `--upstream ...` or
`--route <name>=<upstream>|<header>|<prefix>|<query>`, which `VaultServeCommand.route` reads into
`VaultProxy.Route`.

1. **The provider says where its cost is.** A provider file gains
   `reports_cost: {field: <name in the usage object>, per_usd: <units to the dollar>}` - `openrouter.yaml`
   `{field: cost, per_usd: 1}`; `xai.yaml`, when [P07](../providers/README.md#p07) adds it,
   `{field: cost_in_usd_ticks, per_usd: 10000000000}`. `ProviderDefinitionReader` reads it, refusing a field name
   that is not a plain JSON name or a `per_usd` that is not a positive integer; `ProviderDefinition` gains
   `reportsCost` (`null` for none) with the existing constructors kept, so the agent API only grows. `Destination`
   carries it from `SelectedProvider`.
2. **The broker reads it.** `CredentialWiring` passes it per route (`--cost <route>=<field>|<per_usd>`, the primary
   as `--cost-field`/`--cost-per-usd`); `VaultProxy.Route` gains it. [B148](README.md#b148)'s `UsageReading` takes
   the named field from the same `usage` object it takes the tokens from, in both dialects, as a `BigDecimal` divided
   by `per_usd`, and the usage line gains `cost` in dollars as a decimal string - absent for a route that reports
   none, `"not reported"` where the route declares a cost and the answer carried none.
3. **Where it is set.** `Limits` gains `cost` (`BigDecimal`, dollars, `null` for none) from the project file's
   `limits.cost`, as [B138](README.md#b138) adds `tokens`; `task start --cost-limit <usd>` lowers it for one task,
   never raises it; the task's record keeps it for a resume; `sokar task limit <task> --cost <usd>` raises it through
   the same host-side file [B138](README.md#b138) reads per request.
4. **Refused at the start where it cannot hold.** A task with a cost ceiling and a model provider among its routes
   whose definition has no `reports_cost` does not start: `sokar: the cost ceiling needs every provider to report
   cost; '<name>' does not - use limits.tokens for it`. A destination that is not a model provider is outside the
   ceiling, and the docs say so.
5. **Where it holds.** In `VaultProxy.handle`, beside [B138](README.md#b138)'s check: when the task's summed cost
   has reached the ceiling, the same refusal shape and status [B138](README.md#b138) settles, saying
   `Sokar: this task's cost ceiling of $<n> is reached`; nothing goes to the provider. An answer from a route that
   declares a cost and carried none ends the counting fail closed: the next request is refused, saying the cost of an
   answer could not be read, since a ceiling that let it pass would silently do less. A request already forwarded
   finishes, so one answer can pass the ceiling.
6. **Docs:** `doc/project-file.md` (`limits.cost`), `doc/commands.md` (`--cost-limit`, `task limit --cost`),
   `doc/credentials.md` (`reports_cost` in a provider file), `doc/reach.md` and `doc/corporate-security.md` (what the
   money ceiling covers: providers that report cost, model providers only, one answer past it).

Red first: a `VaultProxyTest` with a stand-in provider streaming an openai answer whose last event carries
`usage.cost`, and a recorded cost at the ceiling - today forwarded, it must be refused without reaching the stand-in;
then `ProviderDefinitionReaderTest` for `reports_cost`, `ProjectReaderTest` for `limits.cost`, and a start test with
a provider that reports no cost refused.

## Acceptance

- With a stand-in provider that reports `usage.cost`, a task at its ceiling gets the refusal, the stand-in receives
  nothing, and `task limit --cost` lets the next request through; seen to fail first with the check removed.
- A task with `limits.cost` and a provider without `reports_cost` does not start and names that provider; seen to
  fail first as a task that starts with a ceiling that never moves.
- An answer missing the declared cost refuses the next request; seen to fail first as a request forwarded.
- `ProviderDefinitionReaderTest` refuses a `reports_cost` with no field or a zero `per_usd`.
- The pages named in step 6 say it.

## To be checked

- Whether `openrouter`'s anthropic-messages dialect carries `usage.cost` in its `message_delta` event as its openai
  dialect does in the last chunk; its documentation says only where usage appears. Measured with a live request
  before `reports_cost` is declared for both dialects (Agent Core's).
- That `openrouter`'s credits are dollars one to one, read in its documentation, before `per_usd: 1` is written.
- For xAI, whether [B148](README.md#b148) asks for usage in a streamed request; if it records "not reported" instead,
  every streamed xAI answer would stop a task with a cost ceiling at once (Agent Core's).
