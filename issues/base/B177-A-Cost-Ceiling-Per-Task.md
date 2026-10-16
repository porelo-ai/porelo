# B177 — A Cost Ceiling Per Task

**Status:** Analyzed; blocked by porelo [B138](README.md#b138) and porelo [B191](README.md#b191).
**Size:** M - a field in the provider file and `ProviderDefinition` (an addition to the agent API), the cost taken
from [B148](README.md#b148)'s record or priced by [B191](README.md#b191)'s table in the broker, `limits.cost` and its
refusal beside [B138](README.md#b138)'s, and docs; no varlink change beyond what [B148](README.md#b148) adds.

**What must be true.** A project may cap a task's spending in US dollars, beside the token ceiling of
[B138](README.md#b138), for every provider: from the cost the provider reports where it does, else at list price from
[B191](README.md#b191)'s table. A model with no price refuses the ceiling - at the start where that is known, else
fail closed - and is never counted as $0. Under a subscription the ceiling caps the list-price value of what was
used, not the money paid.

## Why

A token ceiling bounds use; a person budgets in money, and the same number of tokens costs a hundred times more on one
model than another. The decision "A task's spending is capped in tokens, and in money where a price is known" in
`doc/decisions.md` says where the money comes from. Under a subscription nothing more is billed, but a looping agent
still uses up its limit, and the list-price value is what bounds it there.

**Which providers report a cost**, read in their public API documentation on 2026-10-11:

- **`openrouter`: yes**, in every answer without being asked, as `usage.cost` in credits; streamed, in the last event
  before the end.
- **xAI ([P07](../providers/README.md#p07)): yes**, as `usage.cost_in_usd_ticks`, 10^10 ticks to the dollar; in a
  streamed openai-dialect answer only in the last chunk, which [B148](README.md#b148) asks for.
- **`anthropic`, `github-copilot`, OpenAI ([P05](../providers/README.md#p05)), Google
  ([P06](../providers/README.md#p06)), Zhipu ([P08](../providers/README.md#p08)): no**, token counts only; priced from
  the table.

## Plan

Built on [B138](README.md#b138), [B148](README.md#b148) and [B191](README.md#b191); none exists in the code yet, so
the names below are theirs. Read in today's code: a provider is data (`providers/*.yaml`), read by
`ProviderDefinitionReader` into `ProviderDefinition` (`agents/api`), selected into a `Destination` (`app-base`); the
start (`CredentialWiring`) passes each destination to `porelo vault serve` as `--upstream ...` or
`--route <name>=<upstream>|<header>|<prefix>|<query>`, which `VaultServeCommand.route` reads into
`VaultProxy.Route`.

1. **The provider says what its reported cost is worth.** [B148](README.md#b148) keeps the cost member's name and
   value as reported. A provider file gains `reports_cost: {field: <name in the usage object>, per_usd: <units to
   the dollar>}` - `openrouter.yaml` `{field: cost, per_usd: 1}`; `xai.yaml`, when [P07](../providers/README.md#p07)
   adds it, `{field: cost_in_usd_ticks, per_usd: 10000000000}`. `ProviderDefinitionReader` refuses a field name that
   is not a plain JSON name or a `per_usd` that is not a positive integer; `ProviderDefinition` gains `reportsCost`
   (`null` for none) with the existing constructors kept, so the agent API only grows.
2. **The broker prices each answer.** `vault serve` is given the task's price table, read once at the start from
   [B191](README.md#b191)'s directories (`--prices <dir>`, both passed in order) and each route's `reports_cost`.
   After each answer it takes the reported cost where the route declares one and the answer carried it, else the
   list price of the model answered by [B191](README.md#b191)'s `UsageCost`, and adds it to the task's sum; the
   usage line gains `cost` in dollars as a decimal string and `cost_from: reported | list`.
3. **Where it is set.** `Limits` gains `cost` (`BigDecimal`, dollars, `null` for none) from the project file's
   `limits.cost`, as [B138](README.md#b138) adds `tokens`; `task start --cost-limit <usd>` lowers it for one task,
   never raises it; the task's record keeps it for a resume; `porelo task limit <task> --cost <usd>` raises it through
   the same host-side file [B138](README.md#b138) reads per request.
4. **Refused at the start where it cannot hold.** A task with a cost ceiling whose model provider neither reports a
   cost nor has a price file does not start, and nor does one whose model, named by the start or the agent's
   definition, is missing from that file: `porelo: the cost ceiling needs a price for '<provider>' '<model>' - add it
   to ~/.local/share/porelo/prices/<provider>.yaml or use limits.tokens`. A destination that is not a model provider is
   outside the ceiling, and the docs say so.
5. **Where it holds.** In `VaultProxy.handle`, beside [B138](README.md#b138)'s check: when the task's sum has reached
   the ceiling, the same refusal shape and status [B138](README.md#b138) settles, saying `Porelo: this task's cost
   ceiling of $<n> is reached`; nothing goes to the provider. An answer that cannot be priced - a model answered that
   the table does not hold, a declared cost missing, or a `null` count - stops the counting fail closed, as
   [B138](README.md#b138) does with a `null` count: the next request is refused, saying which, until `task limit`
   raises or lifts the ceiling. A request already forwarded finishes, so one answer can pass the ceiling.
6. **Docs:** `doc/project-file.md` (`limits.cost`), `doc/commands.md` (`--cost-limit`, `task limit --cost`),
   `doc/credentials.md` (`reports_cost` in a provider file), `doc/reach.md` and `doc/corporate-security.md` (what the
   money ceiling covers: model providers only, the reported cost or the dated list price, one answer past it, and
   under a subscription the list-price value, not the money paid).

Red first: a `VaultProxyTest` with a stand-in provider streaming an openai answer whose last event carries
`usage.cost`, and a recorded cost at the ceiling - today forwarded, it must be refused without reaching the stand-in;
the same with a stand-in that reports no cost and a price file; then `ProviderDefinitionReaderTest` for
`reports_cost`, `ProjectReaderTest` for `limits.cost`, and a start test with a model the table lacks refused.

## Acceptance

- With a stand-in provider that reports `usage.cost`, a task at its ceiling gets the refusal, the stand-in receives
  nothing, and `task limit --cost` lets the next request through; seen to fail first with the check removed.
- With a stand-in that reports no cost, the sum follows the price file, and a changed price changes the next sum;
  seen to fail first as a sum that stays at $0.
- A task with `limits.cost` and a model with no price does not start and names the provider and model; seen to fail
  first as a task that starts with a ceiling that never moves.
- An answer from a model the table lacks, or missing its declared cost, refuses the next request; seen to fail first
  as a request forwarded.
- `ProviderDefinitionReaderTest` refuses a `reports_cost` with no field or a zero `per_usd`.
- The pages named in step 6 say it.

## To be checked

- Whether `openrouter`'s anthropic-messages dialect carries `usage.cost` in its `message_delta` event as its openai
  dialect does in the last chunk; its documentation says only where usage appears. Measured with a live request
  before `reports_cost` is declared for both dialects (Agent Core's).
- That `openrouter`'s credits are dollars one to one, read in its documentation, before `per_usd: 1` is written.
- Where the start learns the model when neither the start nor the agent's definition names one, so step 4 can refuse
  at the start rather than at the first answer (Agent Core's).
