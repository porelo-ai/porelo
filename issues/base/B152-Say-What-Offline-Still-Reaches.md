# B152 — Say What Offline Still Reaches

**Status:** Analyzed.
**Size:** S - wording on six pages and one documents test.

**What must be true.** Every page that describes `offline` says that its agent still reaches its AI provider, so "nothing leaves" is not read as a data protection statement.

## Why

An offline task's model calls go to its provider through the host's broker, as in every class; `security.md` said "nothing leaves".

**Guideline points it answers:** Data protection reading of the security classes. From a review of security guidelines, 2026-10-09.

## The shape

`security.md` and its offline picture already say it (2026-10-09, decided: keep the route and say it); `how-it-works.md`, `reach.md` and `faq.md` follow.

## Plan

**Today (read 2026-10-11).** `doc/security.md` says it under `### offline`. Still read as "nothing leaves":
`how-it-works.md` ("`offline`: nothing resolves and nothing leaves"), `project-file.md` ("no network at all, not even
for the host"), `faq.md` ("An `offline` project's work stays on the machine"), `reach.md` (only "Work, in `offline`:
Does not leave at all"), `corporate-security.md` ("`offline` work never leaves") and `index.md`.

**Steps:**

1. Red first: a test in `apps/app`, tagged `documents`, that for each of these pages finds the sentence or table row
   that describes `offline` and fails unless it names the provider - red on `how-it-works.md` first.
2. Each says that the agent's model calls still go to its provider through the host's broker; `reach.md` gets a row
   for them beside the one for work.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- None.
