# B743 — A Transport's Verb Fenced To What It Reaches

```json
{
  "$schema": "issue-1.json",
  "id": "B743",
  "title": "A Transport's Verb Fenced To What It Reaches",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "Every verb of a transport runs in a network namespace of its own whose ruleset lets it reach only what its `reaches` names and loopback, so a transport's mistake cannot reach another host.",
  "openQuestions": 0
}
```

**Why this size.** L: built as three issues of M, (a) to (c) under *Plan*. Each is estimated at Core's median for M,
63 minutes (p80 229).

**What must be true.** A transport's verb that connects to a host outside its `reaches` fails; one inside it passes;
an offline project's verb reaches loopback alone.

Filed 2026-11-13 from [B199](README.md#b199), as the operator decided: B199 makes the pages say what holds and records
the accepted risk; this builds the rule. What it fences is a transport's mistakes, not a hostile transport: the user
bus and unix sockets stay open to it.

Read in today's code (B199's reading): every verb runs as a plain process of the account through `context.runner()`
under `HelperEnvironment.chosen`, with the account's network and user bus. The host's nftables are out of the
account's reach, and a user scope's `IPAddressDeny` is ignored by a user manager (measured on systemd 259). Shield's
nftables apply only inside a container's network namespace.

## Plan

- **(a) A fenced runner.** One verb started in `unshare --user --net`, joined to the outside by `pasta`, with an
  nftables ruleset in that namespace allowing `reaches` and loopback. Red first: a verb connecting to an address
  outside `reaches` is refused.
- **(b) Every verb through it.** The account's own homeserver on the host's loopback is mapped in. `setup`, which runs
  before `reaches` is known, is allowed only what the project's settings name.
- **(c) Names and the VM.** Host names in `reaches` are resolved into the allowed set as `DnsPolicy` does for a task.
  The acceptance runs on the VM.
- To be measured first, on each supported host: whether `pasta` is present, and how a verb's namespace reaches the
  account's homeserver on the host's loopback.

## Acceptance

- On the VM, a transport verb connecting to a host outside `reaches` fails, and one inside it passes.
- An offline project's verb reaches loopback alone.
- The pages B199 corrected say that the rule now holds, and the accepted-risk row is closed.
