# B191 — What A Task Cost At List Prices

**Status:** Analyzed; blocked by sokar [B148](README.md#b148).
**Size:** L - price data and its reader, a declaration of the account's own, sums in two commands and a daemon
method, and a design of what a subscription saved to decide first.

**What must be true.** Sokar says what each task, agent and model cost at the providers' list prices, from
[B148](README.md#b148)'s usage record, with the date the prices were taken from, and what a subscription the person
declared saved against that. A request with no price is said, never counted as $0.

## Why

[B148](README.md#b148) counts tokens, but a person budgets in money, and the same tokens cost a hundred times more on
one model than another. Most of the work runs on subscriptions, whose bill says nothing of what was used; only a sum
at list prices says whether a subscription pays and what an agent or a model costs against another.

## The shape

A receipt, over one task or all of them:

    Usage 2026-10-01 – 2026-10-14            prices of 2026-10-14
      by agent    Claude Code          $412.80
                  Pi                    $63.10
      by model    claude-opus-…        $398.20
                  gpt-…                 $77.70
      subtotal at list prices          $475.90
      subscription  Anthropic Max      -$200.00 (a month, since 2026-09-01)
      saved                            $275.90
      12 requests have no price; 84,300 tokens left out of the sum

## Plan

Read in today's code: providers are data, found by `ProviderDirectory` (`agents/api`) in the person's
`~/.local/share/sokar/providers` first, then the packaged directory (`AgentPaths.providerDirectory`). Nothing on the
host knows a price. [B148](README.md#b148)'s record has one line per request with agent, provider, credential,
model answered and the five counts; its `UsageSums` (`core`) sums them.

1. **The price table** is data, one file per provider, `prices/<provider>.yaml` in this repository and packaged
   beside the provider files, the person's own `~/.local/share/sokar/prices` first, as providers are found: per
   model, `input`, `output`, `cache_read` and `cache_write` in US dollars per million tokens (a missing cache price
   is the input price; `cache_write` only where it differs), and the `taken` date the prices were read on. A change
   of price is a file, not a release. `PriceTable` and its reader in `core`, refusing a negative price, a model
   listed twice, or a file with no `taken`.
2. **What a request cost.** `UsageCost` in `core` prices each line of a record by its provider and model answered
   (the model asked for when the answer named none), from the counts that are not `null`. A line with no price - no
   file, no model in it, or no count to price - is left out and counted: "n requests have no price, x tokens left out
   of the sum". Where the provider reported a cost ([B148](README.md#b148) keeps it), that one is shown beside the
   list price and not replaced by it. **A model reached through a subscription that sells it under no per-token
   price of its own** - Copilot's - is priced from its provider's file where that provider publishes per-token rates
   of its own; else at the list price of the vendor that makes the model (Anthropic's for a Claude model, OpenAI's
   for a GPT model), named in the provider's price file as `priced_as: <vendor>`, and the receipt says so beside the
   sum - "priced at Anthropic's list price" - so it is not read as that provider's price.
3. **A subscription is declared once by the person**: `sokar subscription set <name> --provider <provider>
   [--credential <name>] --monthly <usd> --since <date>`, kept in the account's own config directory as
   `subscriptions.yml`, owner-only, never in a project or a committed file; `subscription list` and `subscription
   remove`. It covers the requests made with that provider (and credential, where named) from its date on.
4. **What it saved**, per calendar month the sum spans: the list-price sum of the requests it covers, less its
   monthly fee for each month it was held, said as a line of its own; a sum that is less than the fee says the
   subscription cost more than its use. A single task is not given a share of the fee: its receipt says which
   subscription covered which part.
5. **Shown** by `task status` for one task (by agent, by model, subtotal, the price date) and by `sokar usage
   [--since <date>] [--until <date>] [--json]` over every task's record, removed ones included, as the receipt
   above; the daemon gains `Cost(task: ?string, since: ?string, until: ?string) -> (cost: Cost)` in `15-task.varlink`
   - an addition. The interface is Frontend's, later.
6. **Docs:** a section in `doc/reach.md` on what the sums mean and that list prices go stale with their date shown;
   `doc/commands.md` for `usage` and `subscription`; `doc/credentials.md` for the price files.

**Red first:** a `UsageCostTest` over a recorded usage file with one priced model, one model missing from the table
and one aborted line with `null` output - the subtotal, the unpriced count and the tokens left out as expected, where
today nothing prices a record; then the reader's test for a file without `taken`, and a `subscription` test that the
file is owner-only and the saving per month is as computed by hand.

## Acceptance

- `task status` and `sokar usage` give the sums by agent and model, at list prices, with the price date; seen to fail
  first on a record that holds a model the table does not.
- A request with no price is named in the count and never adds $0; seen to fail first as a sum that is silently
  lower.
- A declared subscription shows its fee and what it saved per month; the file holds nothing but the declaration and
  is `rw-------`.
- A price changed in `~/.local/share/sokar/prices` changes the next sum without a release.
- The pages named in step 6 say it.

## To be checked

- Whether GitHub publishes per-token rates of its own per model for Copilot: it is said to bill in tokens converted
  to "GitHub AI Credits" (1 credit = $0.01) since 2026-06-01, by third-party sources only. Confirmed in GitHub's own
  documentation, those rates go into `prices/github-copilot.yaml` like any provider's; otherwise it carries
  `priced_as` per model (Agent Core's).
- The list prices of the providers shipped and of [P05](../providers/README.md#p05) to
  [P08](../providers/README.md#p08), read on one date from their price pages, and whether any has a price that
  depends on more than the model and the token kind (a long-context tier, a batch discount) that the table must
  carry (Agent Core's).
- Whether a cache read priced as input where a provider names no cache price overstates the sum enough to say so.
