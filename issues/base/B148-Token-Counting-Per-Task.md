# B148 — Token Counting Per Task

**Status:** open.

**What must be true.** The broker counts each task's tokens per request, from the provider's own answer, and keeps the counts structured per task - with, for each request, the model asked for and the model the provider answered with.

## Why

Nothing counts what a task used; a ceiling (B138) and an inventory (B156) need the count first, and the models a
task's work was made with (B144) need the record of which model answered, whatever the agent was told at its launch
or switched to during its work.

**Guideline points it answers:** AISVS 12.1.3, 12.2.5. From a review of security guidelines, 2026-10-09.

## The shape

Counted where the broker relays the answer; written per task beside its journal; shown by `task status` and the daemon.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Providers that do not report usage in their answer.
