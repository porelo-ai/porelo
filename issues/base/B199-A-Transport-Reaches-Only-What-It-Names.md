# B199 — A Transport Reaches Only What It Names

```json
{
  "$schema": "issue-1.json",
  "id": "B199",
  "title": "A Transport Reaches Only What It Names",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "A transport's adapter reaches only the hosts its `reaches` names, as `doc/transports.md` promises; today nothing builds a rule from them. The pages say what holds and the risk is accepted (XS); the rule itself is [B743](README.md#b743).",
  "openQuestions": 0
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

**The pages say what holds (XS), as the operator decided on 2026-11-13.**

1. `doc/transports.md`, `reaches`: what the project's messages reach, which Porelo shows (`talk`, doctor) and checks
   for an offline project; the transport runs as the account's own program with the account's network, and Porelo
   does not confine it. Installing a transport trusts it as any program of the account.
2. `doc/decisions.md`: the same correction in the homeserver entry, and an accepted-risk row in the index: the exposure
   (an installed transport can reach any host the account can), why it stays (the transport is the person's own
   program, holding the person's credentials and user bus, which no network rule would fence), and what would change
   the answer (transports installed from somewhere other than the person's choice, or a project class that must not
   rely on the transport's word).

Red first: none in code; the shared docs check and a read of both pages against `TransportLifecycle`.

Building the rule is filed apart as [B743](README.md#b743) (L, Medium).

## Acceptance

- Neither page says Porelo lets a transport reach only `reaches`; both say what is checked (offline
  loopback, `--loopback-only`) and what is not; the decisions' index has the accepted-risk row, linked.

## Answered

- The pages say what holds and the risk is accepted (XS) now; building the rule is filed as [B743](README.md#b743)
  (the operator, 2026-11-13).

## To be checked

- Whether `doc/how-it-works.md`, `doc/reach.md` or `doc/messages.md` repeat the promise in other words, to be corrected
  in the same change.

## Built

Docs only, as the operator decided.

- `doc/transports.md` (`reaches`):
  - `reaches` is the transport's own word.
  - Porelo shows it in `porelo doctor` and in the conversation the daemon describes, and checks it only for an
    offline project: `--loopback-only` is passed to setup, enroll, join, part and members, and a `reaches` naming a
    host beyond loopback is refused after each setup and before a task enrolls.
  - A new bullet, "Porelo does not confine a transport": every verb runs as the account's own program, with the
    account's network, files and user bus.
- `doc/decisions.md`:
  - The homeserver entry and its index row are corrected the same way.
  - A new accepted entry, with its index row, "A transport reaches what the account reaches": the exposure, why it
    stays, and what would change the answer.
  - The rule itself is marked "(not yet built)"; it is [B743](README.md#b743). Docs cite no issue numbers.
- `doc/messages.md` repeated the promise in its offline bullet and now says the same as `transports.md`.
  `doc/how-it-works.md` and `doc/reach.md` make no such promise.
- The pages do not say that an empty `reaches` is refused; that is [B740](README.md#b740).
- Checks: check-readmes and check-doc-site pass. `DecisionsIndexedTest` and `MailboxGuideTest` pass on the build box
  (`buildbox-porelo-15f4d845a16d-20261113T174019Z.log`, 4 tests, 0 skipped).
