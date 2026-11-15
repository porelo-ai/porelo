# B794 — A Token Count Is Partial Only Where a Reply Left It Out

```json
{
  "$schema": "issue-1.json",
  "id": "B794",
  "title": "A Token Count Is Partial Only Where a Reply Left It Out",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 8,
  "blockedBy": [],
  "covers": "Usage counts a request as missing a kind only where its format reports that kind and the reply did not, and its contract says that output includes reasoning in every format, so the window neither says at least for every task nor counts reasoning twice",
  "openQuestions": 0
}
```

**Why this size.** S: one table of the kinds each request format reports, read by `UsageSums`, and contract words
with their tests. Estimate: 38 minutes (Core's median for S).

**What must be true.** A task whose every request answered in full reads as exact, whatever its agent's format; a
total of tokens is output plus input and cache, never output plus reasoning.

Filed 2026-11-15 for ST369 from the operator's live test ("Tokens used: at least 1.5 M" a minute into a task) and
Agent Smith's reading of `UsageReading` and `UsageSums`. The window's side is Frontend's F602, which cites this.

## Why

- `UsageSums` counts a request as missing a kind whenever its line leaves that kind `null`. `UsageReading` writes
  `null` for a kind the request's format never reports: Anthropic's messages report no reasoning (thinking is inside
  `output_tokens`); OpenAI's Responses and chat completions, and Gemini, report no cache write. So every request of
  every agent misses a kind, every total is partial, and the window always says "at least".
- What each format's `output` holds differs in the reader's words only: OpenAI's `completion_tokens` /
  `output_tokens` already include `reasoning_tokens`, Gemini's `output` is made `candidatesTokenCount` plus
  `thoughtsTokenCount`, Anthropic's `output_tokens` include thinking. So `output` includes reasoning in every format,
  and `reasoning` is a part of it, but the contract (`UsageTotals`, `15-task.varlink`) does not say so, and a window
  that adds both counts reasoning twice.

## Plan

1. `UsageReading` keeps the `format` it writes on each line; `UsageSums` takes, per format, the kinds that format
   reports (messages: input, cache read, cache write, output; responses and chat completions: input, cache read,
   output, reasoning; Gemini: input, cache read, output, reasoning). A kind the format does not report is not counted
   as missing and adds nothing; a kind it reports that the reply left out (a cut stream, a failed request) is missing,
   as today. A line of a format Porelo does not know counts every `null` as missing, as today.
2. `UsageCount.tokens` stays `null` only where no request of the set reported the kind at all.
3. `15-task.varlink`: `UsageTotals` says that `output` includes `reasoning` for every format, that `reasoning` is
   the part of it a format names (absent for a format that names none), and that `missing` counts only requests whose
   format reports the kind; a total is `input + cacheRead + cacheWrite + output`.
4. `doc/` where usage is described, and a changelog entry under *fixed*.

## Red-first test

- `UsageSumsTest`: three complete Anthropic requests with no reasoning, and three complete Responses requests with no
  cache write, sum with `missing` 0 for every kind. Fails today: `missing` 3 for reasoning and for cache write.
- A cut Anthropic request with no output still counts output as missing.
- `UsageReadingTest`: an OpenAI chat reply with `completion_tokens` 100 and `reasoning_tokens` 40 reads output 100 and
  reasoning 40, never 140.

## Acceptance

- The red-first tests pass, each seen red first.
- On a machine, a task whose requests all answered shows its tokens without "at least" in the window (with F602).
