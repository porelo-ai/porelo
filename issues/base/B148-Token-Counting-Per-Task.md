# B148 — Token Counting Per Task

**Status:** Analyzed.
**Size:** L - streamed answers of two dialects read in the broker, a per-task record that outlives the task, and a
contract change for the status.

**What must be true.** The broker counts each task's tokens per request, from the provider's own answer, and keeps the counts structured per task - with, for each request, the model asked for and the model the provider answered with.

## Why

Nothing counts what a task used; a ceiling ([B138](README.md#b138)) and an inventory ([B156](README.md#b156)) need the count first, and the models a
task's work was made with ([B144](README.md#b144)) need the record of which model answered, whatever the agent was told at its launch
or switched to during its work.

**Guideline points it answers:** AISVS 12.1.3, 12.2.5. From a review of security guidelines, 2026-10-09.

## The shape

Counted where the broker relays the answer; written per task beside its journal; shown by `task status` and the daemon.

## Plan

**Today.** The broker is `VaultProxy` (`supervisor/src/main/java/org/fuin/sokar/supervisor/VaultProxy.java`), one per
task, started by `CredentialWiring` as `sokar vault serve --task <task> ...` (`VaultServeCommand`). Its log,
`vault.log` in the task's runtime directory, has one line per request: method, target, status. It reads the whole
request body and relays the answer in chunks in `writeResponse`, passing every chunk through `CredentialScan`
already. Answers are JSON or `text/event-stream` only (`UNDERSTOOD_TYPES`).

**Steps:**

1. `UsageReading` in `supervisor`, fed the request body and each relayed chunk as `CredentialScan` is: the model asked
   for from the request's `model`; the answering model and the tokens from a JSON answer's `model` and `usage`
   (openai: `prompt_tokens`, `completion_tokens`; anthropic-messages: `input_tokens`, `output_tokens` and the cache
   counts) and from streamed events (`message_start`, `message_delta`, a last chunk's `usage`). It never holds back or
   changes a byte; what it cannot read is recorded as "usage not reported".
2. One JSON line per request appended to the task's usage record: time, route, model asked for, model answered,
   tokens in and out, status - never a prompt. Given to `vault serve` as `--usage-file`; kept under the state
   directory as `usage/<container>.jsonl` (`TaskPaths.usageRecord`) and renamed at removal as `TaskControl`
   renames the clearance journal, so [B144](README.md#b144) reads it after a removal.
3. `task status` (`TaskStatusCommand`) sums it per model; the daemon's task status (`TaskMethods`,
   `15-task.varlink`) gains `usage: []ModelUsage` - a contract change.
4. `doc/reach.md` and `doc/credentials.md` say what the record holds and that it holds no prompt.

Red first: a `VaultProxy` test with a stand-in provider streaming an anthropic-messages answer - the record holds one
line with both models and the counts, and the agent received the answer byte for byte.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Providers that do not report usage in their answer: a streamed openai answer reports it only when the request asks
  (`stream_options.include_usage`). Whether the broker adds that to a request or records "not reported" (Agent
  Core's).
- How the interface shows a task's usage (Agent Frontend's).
