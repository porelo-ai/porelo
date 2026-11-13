# B743 — A Transport's Verb Fenced To What It Reaches

```json
{
  "$schema": "issue-1.json",
  "id": "B743",
  "title": "A Transport's Verb Fenced To What It Reaches",
  "state": "Implementing",
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

Filed 2026-11-13 from [B199](README.md#b199), as the operator decided: [B199](README.md#b199) makes the pages say what holds and records
the accepted risk; this builds the rule. What it fences is a transport's mistakes, not a hostile transport: the user
bus and unix sockets stay open to it.

Read in today's code ([B199](README.md#b199)'s reading): every verb runs as a plain process of the account through `context.runner()`
under `HelperEnvironment.chosen`, with the account's network and user bus. The host's nftables are out of the
account's reach, and a user scope's `IPAddressDeny` is ignored by a user manager (measured on systemd 259). Shield's
nftables apply only inside a container's network namespace.

**Decided 2026-11-13 (operator).** `setup` stays unfenced as the one exception. The operator accepted it, and
`doc/security.md` names it as an accepted exception with part (c)'s build. Part (c) goes on without a question about
`setup`.

## Plan

- **(a) A fenced runner.** One verb started in `unshare --user --net`, joined to the outside by `pasta`, with an
  nftables ruleset in that namespace allowing `reaches` and loopback. Red first: a verb connecting to an address
  outside `reaches` is refused.
- **(b) Every verb through it.** The account's own homeserver on the host's loopback is mapped in. `setup`, which runs
  before `reaches` is known, stays unfenced as the one exception.
- **(c) Names and the VM.** Host names in `reaches` are resolved into the allowed set as `DnsPolicy` does for a task.
  The acceptance runs on the VM. With this part, `doc/security.md` names `setup` as an accepted exception.
- To be measured first, on each supported host: whether `pasta` is present, and how a verb's namespace reaches the
  account's homeserver on the host's loopback.

## Built

- Part (a), 2026-11-13 (Codex CLI, gpt-6.1-sol; the lead ran the tests). Measured on the build box (Ubuntu 26.04.1,
  kernel 7.0, `apparmor_restrict_unprivileged_userns=1`): `unshare --user --net` is refused for an unprivileged
  account, while `pasta --config-net -- <command>` runs the command as uid 0 in its own user and network namespace,
  where `nft` loads a ruleset. So the fenced runner `TransportFence` is built on pasta, not unshare: the ruleset is
  loaded inside the namespace before the verb is executed, allowing loopback and the literal addresses `reaches`
  names, IPv6 neighbour discovery only where an IPv6 reach is declared (ICMPv6 types 133-136, hop limit 255,
  link-local), and dropping everything else; an offline project's verb gets loopback alone. A missing pasta, a failed
  pasta or a failed ruleset load refuses and never runs the verb unfenced; a host name in `reaches` is refused until
  part (c). `members` is its first user.
- Box: green, 184 tests, 1 skipped (`TransportFenceMachineTest`, which runs real pasta only when its property and a
  listener are given), buildbox-porelo-3965ba3371bc-20261113T192726Z.log. The new class's own tests could not be red
  at runtime without it; the red run of the tests alone did not compile (buildbox-porelo-da4721be8baa-20261113T192318Z.log).
- Part (b), 2026-11-13 (Codex CLI, gpt-6.1-sol; the lead ran the tests).
  - `TransportVerbs` is the one place a transport's verbs run. Every verb goes through the fence, and so does any
    verb added later.
  - `setup` is the one exception and runs as before. A test pins that it stays the only one. Before its first run,
    Porelo has no port to map for the account's own homeserver, because the transport picks that port inside `setup`.
  - Each loopback port comes from the `reaches` the last `setup` reported, mapped one by one with `pasta -T <port>`,
    never `-T auto`. An offline project keeps those mappings and reaches nothing beyond loopback. Without a project
    policy no external address is allowed.
  - `Clearing` and `VaultClearing` keep the setup's reaches in memory until the project's records are removed.
  - Red: the new tests use the new `TransportVerbs`, so on the base they do not compile. Green, run locally: 366 tests
    in the transport, clearing and message classes and 210 in the other changed classes, 0 failures.
- Part (c) remains.

## Acceptance

- On the VM, a transport verb connecting to a host outside `reaches` fails, and one inside it passes.
- An offline project's verb reaches loopback alone.
- `doc/security.md` names `setup`, which runs unfenced, as an accepted exception.
- The pages [B199](README.md#b199) corrected say that the rule now holds, and the accepted-risk row is closed.
