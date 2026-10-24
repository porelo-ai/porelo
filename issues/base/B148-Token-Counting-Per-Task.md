# B148 — Token Counting Per Task

**Status:** Analyzed.
**Size:** L - four answer formats read in the broker, streamed and not, a request changed for one of them, a
per-task record that outlives the task, sums in `task status` and a new daemon method.

**What must be true.** The broker counts each task's tokens per request, from the provider's own answer, and keeps
the counts structured per task - with, for each request, the agent and the provider, the model asked for and the
model the provider answered with, the tools the answer called, how the answer ended, and the cost where the provider
reports one.

## Why

Nothing counts what a task used; a ceiling ([B138](README.md#b138)), an inventory ([B156](README.md#b156)) and what a
task cost ([B191](README.md#b191)) need the count first, and the models a task's work was made with
([B144](README.md#b144)) need the record of which model answered, whatever the agent was told at its launch or
switched to during its work.

**Guideline points it answers:** AISVS 12.1.3, 12.2.5. From a review of security guidelines, 2026-10-09.

## The shape

Counted where the broker relays the answer; written per task beside its journal; summed by `task status` and the
daemon. Nothing is read from the text of a prompt or an answer - no prompts counted, no phrases kept; a line holds
counts, names and states only.

**The formats, read from the providers' documentation, nothing measured yet.** The reader picks one by the request's
path and the answer's shape, never by the provider's dialect: `github-copilot` serves `/chat/completions`,
`/responses` and `/v1/messages` under the one dialect `openai`.

| Format | Paths | Where the count is |
|---|---|---|
| Chat Completions | `…/chat/completions` | `usage.prompt_tokens`, `completion_tokens`; streamed, in a chunk with empty `choices` |
| Responses | `…/responses` | `usage.input_tokens`, `output_tokens`; streamed, in the `response.completed` event |
| Anthropic Messages | `…/v1/messages` | `message_start` (input, cache) and `message_delta` (output, cumulative) |
| Gemini | `…:generateContent`, `…:streamGenerateContent` | `usageMetadata`, cumulative in every chunk |

**Fields of one meaning across providers**, each `null` when the provider does not report it, never 0:

- `input` - without the cache: Anthropic's `input_tokens` already excludes it; OpenAI's `prompt_tokens` and
  `input_tokens` include `cached_tokens`, which is subtracted.
- `cache_read` - Anthropic `cache_read_input_tokens`, OpenAI `prompt_tokens_details.cached_tokens` and
  `input_tokens_details.cached_tokens`, Gemini `cachedContentTokenCount`.
- `cache_write` - Anthropic `cache_creation_input_tokens`; OpenAI and Gemini report none, so `null`.
- `output` - with the reasoning: Gemini counts `thoughtsTokenCount` apart from `candidatesTokenCount`, so the two are
  added.
- `reasoning` - where reported: OpenAI `reasoning_tokens`, Gemini `thoughtsTokenCount`.

In a stream the **last value that is not null** is kept, never a sum of the chunks: Gemini repeats the count,
cumulative, in every chunk; Anthropic's `message_delta` `output_tokens` is cumulative; xAI has been seen to report
`usage` in a chunk before the last.

## Plan

**Today.** The broker is `VaultProxy` (`supervisor/src/main/java/org/fuin/porelo/supervisor/VaultProxy.java`), one per
task, started by `CredentialWiring` (`apps/app-task`) as `porelo vault serve --task <task> ...`
(`apps/app-vault/.../VaultServeCommand.java`). `handle` reads the whole request body (`body`), picks the route by the
token's scope - a route is named by its credential, and knows nothing of its provider or the agent - and
`writeResponse` relays the answer in chunks, passing each through `CredentialScan`. Answers are JSON or
`text/event-stream` only (`UNDERSTOOD_TYPES`). `vault.log` has one line per request: method, target, status. JSON is
read with `org.fuin.porelo.wire.Json`.

**Steps:**

1. **Who and where.** `CredentialWiring` passes the agent's name (`--agent`) and each route's provider
   (`--provider <credential>=<name>`, the primary as `--provider-name`); `VaultProxy.Route` gains `provider`. Every
   line then carries the agent, the provider and the credential, so no sum needs a join.
2. **Which paths count.** Only those that generate, by the path's end: the four in the table. `…/count_tokens`,
   `…:countTokens`, `/models` and every other path are not recorded; the docs say so.
3. **The streamed Chat Completions count is asked for.** A streamed Chat Completions request reports its count only
   when `stream_options.include_usage` is true (OpenRouter reports it always; OpenAI only when asked; xAI, Zhipu,
   Copilot and Gemini's `/v1beta/openai` unknown). The broker sets it to true on every such request, whatever the
   agent sent, and sends the body uncompressed with its length corrected: left to the agent, an agent that sets it
   false escapes [B138](README.md#b138)'s ceiling. The agent gets one more chunk with empty `choices`, the documented
   shape the OpenAI clients read; that the stub, the Pi family and each Chat Completions agent take it is measured
   before this ships, and a provider whose live request refuses the field is named in its provider file with
   `stream_usage: false`, which leaves the request alone and records `null`. The line says `asked: true`.
4. **`UsageReading`** in `supervisor`, one per answer, fed the request body and each relayed chunk as
   `CredentialScan` is: an SSE framer across chunk boundaries for a stream, the whole body for JSON, each bounded
   (an event or body beyond 1 MiB is not parsed and its count is `null`). It reads the model asked for from the
   request, the answering model, the five fields above, the tools called - their names and number per answer
   (Anthropic `tool_use`, Chat Completions `tool_calls[].function.name`, Responses `function_call`, Gemini
   `functionCall`), never their arguments - and the cost the provider reports where it does (`usage.cost` at
   OpenRouter, `usage.cost_in_usd_ticks` at xAI), kept as the member's name and its value as a decimal string, in
   the provider's unit. It never holds back or changes a byte of the answer.
5. **How the answer ended:** `complete` (the event carrying the final count arrived, or the JSON body was read),
   `aborted` (the stream closed before it - by the agent, when writing to it fails, or by the provider, when its body
   ends early; `closed_by` says which; Anthropic then still has the input from `message_start`, Chat Completions has
   nothing) or `error` (a status not 2xx, or an `error` event), with the status. A provider bills an aborted request
   all the same, so it is recorded with what it has, never as 0.
6. **One JSON line per request**, appended to the task's usage record: time (UTC), agent, provider, credential,
   format, model asked, model answered, the five counts, `asked`, tools, reported cost, end, `closed_by`, status.
   Given to `vault serve` as `--usage-file`; kept under the state directory as `usage/<container>.jsonl`
   (`TaskPaths.usageRecord`) and renamed at removal as `TaskControl` renames the clearance journal, so
   [B144](README.md#b144) and [B191](README.md#b191) read it after a removal.
7. **Sums.** One `UsageSums` in `core` reads a record and sums it per model, per agent, and per day and hour (the
   host's local time), and counts per field the requests where it is `null`. `task status` (`TaskStatusCommand`)
   prints them; the daemon gains `Usage(task: string) -> (usage: Usage)` in `15-task.varlink` (`TaskMethods`), the
   same sums - a contract change, an addition. `Task` in `List` and `Watch` is not changed, so a watch never reads a
   record.
8. **What [B138](README.md#b138) does with a `null` count.** A `complete` answer without one stops the counting
   fail closed: the next request is refused, saying the count of an answer could not be read, until `task limit`
   raises or lifts the ceiling; a provider whose file has `stream_usage: false` is refused a ceiling at the start, as
   [B177](README.md#b177) does for cost. An `aborted` answer counts what it has; without any count, three in a row
   refuse the next request, so closing every stream before its end does not escape the ceiling, while a person's
   interrupt does not stop the task.
9. **Docs:** `doc/reach.md` and `doc/credentials.md` say what the record holds, that it holds no prompt and no
   answer's text, which paths count, and that the broker asks for the streamed Chat Completions count.
- **Fixtures to record:** one streamed answer of each format OpenAI ([P05](../providers/README.md#p05), Chat
  Completions and Responses) and Google ([P06](../providers/README.md#p06), Gemini) serve, recorded from a live run
  and replayed by `VaultProxyTest` so the fixture line holds every count the provider sent and the agent's answer
  differs only by the added usage chunk.

**Red first.** The stand-in provider of `VaultProxyTest` replays a recorded stream per format - Chat Completions
with and without `include_usage`, Responses, Anthropic Messages, Gemini - plus one cut off mid-stream and one
`count_tokens` call: each gives the line expected (the cut one `aborted`, `count_tokens` none), and the agent
receives every answer byte for byte except the added usage chunk. Today no record exists. For the providers not yet
shipped, [P07](../providers/README.md#p07) and [P08](../providers/README.md#p08) each record their stream in their live
scenario and add it as a fixture.

## Acceptance

- Seen to fail first, then green: the replayed streams above, in `VaultProxyTest`, with the fields, the end and the
  tools as expected and the cache subtracted from OpenAI's input.
- An agent that sends `include_usage: false` gets a counted line; seen to fail first as a line with `null` counts.
- `task status` and `Usage` give the sums per model, per agent, per day and hour, and the number of requests with a
  field `null`; seen to fail first on a record with an aborted line.
- A removed task's record is still read; seen to fail first as a record deleted with its task.
- The pages named in step 9 say it.

## To be checked

- That the stub, the Pi family and each agent speaking Chat Completions take the extra usage chunk, measured before
  step 3 ships (Agent Core's).
- Whether xAI, Zhipu, Copilot and Gemini's `/v1beta/openai` accept `stream_options.include_usage` and report the
  count, measured in [P07](../providers/README.md#p07) and [P08](../providers/README.md#p08)'s live scenarios and for
  Copilot with a live request (Agent Core's).
- How the interface shows a task's usage (Agent Frontend's).
