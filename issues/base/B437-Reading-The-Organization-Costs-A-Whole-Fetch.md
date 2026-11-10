# B437 — Reading The Organization Costs A Whole Fetch

**Status:** Implementing, since 2026-11-10. In `porelo-project` ST308; the operator asked for it on 2026-11-10.
**Size:** M - a read answers from the kept state and refreshes after; every security check keeps reading fresh.

**What must be true.** A question that shows the organization or a project to a person answers without waiting on a whole
fetch of the organization's repository, and a decision that rests on the organization still fetches.

## Why

`porelo org --json` and `porelo project show` on a project this machine does not hold each take about 2 s, so a page's
content comes well after its head. On an empty account both take 0.03 s, so the program's own start is not the cost.

Read in the code: every read goes `GitTreeStore.read` to `TreeRepository.fetch`, a scratch clone of the whole repository in a
temporary directory each time, then a walk from the commit kept, and the forge's lists of keys and owners over http.
`project show` gets there through `ProjectTaking`. Nothing asks first whether the tip moved.

Measured from one machine, the pieces against github.com: an ssh handshake 1.0 s, `git ls-remote` over https 0.4 s, a small
bare clone over https 0.5 s, one `<login>.keys` page 0.2 s. The whole of it was not measured against a real organization: only
an operator's account holds one, and it reads with their agent and vault.

## To be checked

- How much of the 2 s is each piece on a real organization, with `GIT_TRACE2_PERF` or a timer around the fetch, the walk and
  the forge's calls.
- Whether answering from `GitTreeStore.last()` at once and refreshing after is the right shape. That answer has no history
  to check against, so no security check may rest on it, and it must say how old it is. A flag for a page that only shows,
  or a fetch only when `ls-remote` shows a new tip: the second still costs the handshake.
- Whether a person's `project show` should take a project at all, or only read what is placed.

## Decided

After the next test. A read of the organization answers from the kept state first and refreshes after. No security check rests
on the kept state: a start's and a host's checks go on reading fresh. The window marks a stale value until the refresh arrives,
such as a role just withdrawn.
