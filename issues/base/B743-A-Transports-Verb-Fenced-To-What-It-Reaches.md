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

- **How far it got (back to Backlog, 2026-11-14).** On main: parts (a), (b) and (c) with their reading's fixes
  (`484445545`, `0b8f080f9`, `06164ef1d`). Left: the acceptance run on the VM. The branches `core-b743b` and `core-b743n`
  hold only earlier versions of those commits.
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
- Part (c) (Codex CLI, gpt-6.1-sol; the lead fixed one test assertion and ran the tests).
  - A host name in `reaches` is resolved once per launch, through an injectable resolver, into the literal addresses
    the fence allows; a name that resolves to nothing refuses the verb.
  - Names inside the fence resolve only through the host's own resolvers. Where every nameserver in the host's
    `resolv.conf` is on loopback (systemd-resolved at `127.0.0.53`), pasta forwards port 53 into the namespace
    (`-U 53`, `-T 53`); otherwise UDP and TCP port 53 are allowed to those resolver addresses alone. An offline
    project gets none.
  - `doc/security.md` names `setup` as the accepted exception, DNS as a side channel and a name's addresses rotating
    within a run; `doc/transports.md`, `doc/messages.md` and `doc/decisions.md` say what holds now.
  - Red: the tests do not compile on the base, which has no `Resolver`. Green, on the host: 593 tests in the
    transport, clearing, message and start classes, 0 failures, 0 skipped, with `TransportFenceMachineTest` against
    real pasta (`1.1.1.1:443`, the name `one.one.one.one`) passing its 3 tests.
- The reading of part (c) (agy, gemini-4-argon-high), each finding checked in the code:
  - Grave, and it was part (b)'s: pasta runs the verb as root of its user namespace with every capability, so the
    verb could `nft flush ruleset` and reach anything. Measured on the host: with the fence loaded, the verb flushed
    it and connected to `1.1.1.1:443`. Now `setpriv --bounding-set=-all --inh-caps=-all --ambient-caps=-all
    --no-new-privs` runs before the verb, and a missing setpriv refuses it. `TransportFenceMachineTest`'s
    `aFencedVerbCannotLiftItsOwnFence` was red (flushed, connected), then green (refused); the unit tests on the
    command and a missing setpriv were red first too.
  - A name answering loopback mapped the reach's port to the host's listener, and an unspecified, multicast,
    broadcast or link-local address was accepted in a reach or an answer. Both are refused now, tests seen red then
    green. A literal loopback reach and `localhost` keep their mapping.
  - The nameserver parse error is now kept as the cause.
  - Filed: one project's unbuildable fence ends the delivery pass for every project ([B759](README.md#b759)).
  - Declined: `[::ffff:127.0.0.1]` and `127.0.0.2` read as loopback here and not in `TaskConversations` - a
    literal loopback reach is mapped either way, so nothing widens; precompiled patterns - a few lines per verb.
- Green on the host after these: 609 tests in the transport, clearing, message and start classes, 0 failures, with
  the 4 real-pasta machine tests.
- Still open: the acceptance run on the VM. Until it has run, the real-pasta test on the host is what is measured.

## Acceptance

- On the VM, a transport verb connecting to a host outside `reaches` fails, and one inside it passes.
- An offline project's verb reaches loopback alone.
- `doc/security.md` names `setup`, which runs unfenced, as an accepted exception.
- The pages [B199](README.md#b199) corrected say that the rule now holds, and the accepted-risk row is closed.
