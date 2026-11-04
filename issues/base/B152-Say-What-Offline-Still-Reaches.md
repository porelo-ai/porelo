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

**Read in the code on 2026-11-04.** `doc/security.md` says it under `### offline`; `doc/reach.md` names model
calls through the broker and declared host services in its offline row. `doc/how-it-works.md` says no external host
resolves and no Git work leaves on its own; `doc/project-file.md` says no network beyond this host and allows
`host_services`; `doc/faq.md` names the agent's and provider's hosts before its offline restriction.
`doc/corporate-security.md` still says "`offline` work never leaves"; `doc/index.md` says "`offline` sends nothing
upstream". These descriptions do not all explicitly say that offline model calls still reach the provider.

**Steps:**

1. Red first: a test in `apps/app`, tagged `documents`, that for each of these pages finds the sentence or table row
   that describes `offline` and fails unless it names the provider - red on `how-it-works.md` first.
2. Each says that the agent's model calls still go to its provider through the host's broker; `doc/reach.md` keeps
   its explicit description of them in the row for offline work.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.


