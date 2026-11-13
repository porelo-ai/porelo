# B199 — A Transport Reaches Only What It Names

```json
{
  "$schema": "issue-1.json",
  "id": "B199",
  "title": "A Transport Reaches Only What It Names",
  "state": "Accepted",
  "size": "XS",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "A transport's adapter reaches only the hosts its `reaches` names, as `doc/transports.md` promises; today nothing builds a rule from them. Recommended: the pages say what holds (XS); building the rule is L.",
  "openQuestions": 1
}
```

**Why this size.** Sized for the recommended answer below: two pages say what holds instead of a rule nothing builds,
`doc/transports.md` and `doc/decisions.md`, with an accepted risk in the decisions' index; no code, no contract.
Estimate: under an hour, below Core's median for S (45 minutes). Built as a rule instead it is L, split as said under
*Plan*.

**What must be true.** A transport's adapter reaches only the hosts its `setup` names in `reaches`, as
`doc/transports.md` promises ("Porelo lets the transport reach those and nothing else") - or the page says what holds
instead.

**Why.** Read in the code on 2026-10-17: Porelo reads `reaches` in one place, `TaskConversations` (`app-messaging`),
to refuse an offline project whose conversation leaves loopback. Nothing builds a rule from it. The adapter runs on the
host as the account's own process, with the account's whole network, so a transport could reach any host. Found
answering porelo-message-matrix's question on a host without a port in `reaches`.

## Plan

Read in today's code: `reaches` is read in two places, both only for an offline project: `TaskConversations` before a
task enrolls, and `TransportLifecycle.setupHeld` after every `setup`, which also passes `--loopback-only` to `setup`,
`enroll`, `join`, `part` and `members`. `Conversations` and `DoctorCommand` only show it. Every verb, `poll` and `send`
included (`TransportPoll`, `TransportSend`, `TransportConversations`), runs through `context.runner()` under
`HelperEnvironment.chosen`: a plain process of the account, with the account's network, its files, and its user bus
(`DBUS_SESSION_BUS_ADDRESS` is passed on). `doc/transports.md` (`reaches`) and `doc/decisions.md` ("The homeserver is
one endpoint Porelo names, and offline means loopback": "the host's egress for a project's messages is what the
transport's `setup` reports in `reaches`, and nothing else") both promise a rule that does not exist.

What Porelo has for a rule is the container's: `shield` (`NftRuleset`, `DnsPolicy`, `EgressPolicy`) loads nftables into a
task container's own network namespace through the OCI hook. The host offers no such place for a host process: the
account is not root, so the host's nftables are out of reach, and a user scope's `IPAddressAllow`/`IPAddressDeny` is
ignored by the user manager (measured on systemd 259: a scope with `IPAddressDeny=any` still connected to an outside
address, and the journal says "unit configures an IP firewall, but not running as root"). What is left is a user and
network namespace per verb, joined to the outside through `pasta`, with a ruleset inside it.

**Recommended: the pages say what holds (XS).**

1. `doc/transports.md`, `reaches`: what the project's messages reach, which Porelo shows (`talk`, doctor) and checks
   for an offline project; the transport runs as the account's own program with the account's network, and Porelo
   does not confine it. Installing a transport trusts it as any program of the account.
2. `doc/decisions.md`: the same correction in the homeserver entry, and an accepted-risk row in the index: the exposure
   (an installed transport can reach any host the account can), why it stays (the transport is the person's own
   program, holding the person's credentials and user bus, which no network rule would fence), and what would change
   the answer (transports installed from somewhere other than the person's choice, or a project class that must not
   rely on the transport's word).

Red first: none in code; the shared docs check and a read of both pages against `TransportLifecycle`.

**If the rule is built instead (L), split into three of M:** (a) a runner that starts one verb in `unshare --user
--net` joined by `pasta`, with an nftables ruleset in that namespace allowing `reaches` and loopback, red first a
verb that connects to an address outside `reaches` and is refused; (b) every verb through it, with the account's own
homeserver on the host's loopback mapped in, and `setup` (which runs before `reaches` is known) allowed only what the
project's settings name; (c) names resolved into the allowed set as `DnsPolicy` does for a task, and the acceptance
run on the VM. Even then the user bus and unix sockets stay open, so the rule fences a transport's mistakes, not a
transport.

## Acceptance

- Recommended answer: neither page says Porelo lets a transport reach only `reaches`; both say what is checked (offline
  loopback, `--loopback-only`) and what is not; the decisions' index has the accepted-risk row, linked.
- Built answer: a transport verb connecting to a host outside `reaches` is seen failing on the VM, one inside it
  passes, and an offline project's verb reaches loopback alone.

## Open questions

- Build the rule (L, three issues of M, a fence against a transport's mistakes but not against a transport), or change
  the pages to say what holds and accept the risk (XS)? Recommended: change the pages; the transport is the person's
  own program with the person's credentials and user bus, and a network rule on it buys little for its size.

## To be checked

- Whether `doc/how-it-works.md`, `doc/reach.md` or `doc/messages.md` repeat the promise in other words, to be corrected
  in the same change.
- For a built rule only: whether `pasta` is present on every supported host, and how a verb's namespace reaches the
  account's own homeserver on the host's loopback.
