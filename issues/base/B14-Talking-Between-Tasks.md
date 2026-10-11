# B14 — Talking Between Tasks

**Status:** Implementing; blocked by sokar [B86](README.md#b86).
**Size:** XL - groups, trust confirmed once and two machines are three pieces; the plan splits them.

**What must be true.** A person can address a group of peers as one - held, released and closed as one - and a
task's message reaches a task of the same project on another machine with the same guarantees it has on one:
signed on the host, filtered, attributed against a key list, delivered once.

Built and measured on one machine, over a transport that keeps a conversation ([B86](README.md#b86)): the mailbox, the signature,
the sluice both ways, dispatch, delivery against a key list, the record, duplicate suppression, bounces, budgets,
holding and releasing with the four modes, `sokar talk`, and the daemon's message methods. A message addressing
more than one peer is refused with a reason today. How groups would be built is
[B14-Talking-Between-Tasks_design.md](B14-Talking-Between-Tasks_design.md).

## Plan

Read in the code at `30c8169b`.

**What is there.** The messaging lives in `apps/app-messaging/...`: `MessageIntake`, `MessageFiltering`,
`MessageDispatch`, `MessageDelivery`, `Moderation`, `KnownPeers`, `AllowedSigners`, `SharedKeys`; the daemon's methods
are `daemon/.../MessagingMethods.java` with `45-messaging.varlink`. `MessageDispatch.addressees` already reads
`metadata.to` as a string or a list, and a list of more than one is refused. `Moderation` holds or releases one peer
for the whole project (`Moderate(project, name, held)`); the mode is set in `project.yml` (`mail.rules`, a peer's
`mode`). The project file has `mail.peers.<peer>` (`address`, `trust`, `per_day`, `mode`) in `ProjectSchema` and no
`groups`.

**The signed key directory is built.** `KnownPeers` reads, besides `machine-signers` and the shared keys, the
`machine-signers` file each followed, verified project keeps beside its `project.yml`; `project follow` takes only a
signed commit that descends from the one in force. So the third Groups criterion holds today; a test that a
directory signed by another key, or an older one, admits nobody is what is left of it.

**Not built: trust confirmed once.** `Release(task, id, refuse, reason)` has no way to trust a key, and nothing in
the messaging code keeps a fingerprint for a peer outside the directory.

The split, each an issue of its own when it is started:

1. **Groups (M).** `mail.groups.<group>: [peer, ...]` in `ProjectSchema`, `ProjectReader` and `Mail`; a group name
   in `metadata.to` resolves in `MessageDispatch` to its members, each queued over its own transport, the sender's
   receipt naming each; `Moderation` takes a group name and holds, releases or closes all its members' traffic, and
   `Moderate` and `sokar talk hold` accept it. Red first: a test in `MessageDispatchTest` sending to a two-member
   group, red while it is refused as addressing more than one peer; then one holding the group and asserting on what
   reaches each member's inbox.
2. **Trust confirmed once (M).** An arrival signed by a key no list names is held with its fingerprint (as today it
   is held unattributed); `Release` gains `trustKey`, which writes the key into the account's `allowed_signers` for
   that peer; a key that changes for a known peer is held with both fingerprints. Contract: `Release` grows one
   optional field. Red first: a test delivering under a changed key without a person.
3. **Another machine (L, blocked by [B86](README.md#b86)).** Nothing new in the mailbox: the transport carries signed pairs. What is
   left is the two-machine scenario on one homeserver - a task on each writes to the other, the bytes hashed in
   `outbox/new/` and again in the far inbox, a message signed by the other machine's unlisted key held - and a retry
   by the transport delivered once (`MessageDelivery`'s duplicate suppression, measured across machines).

## Acceptance

**Groups**

- A group is a list of peers on the host; a message to it reaches every member over whatever transport each uses.
  Seen to fail: a member missing the message, or a message to a group still refused as addressing more than one
  peer.
- A group can be held, released and closed while it runs; a held message reaches no reader and its sender is
  told. Seen to fail: a member reading a message sent while the group was held.
- Inside a group, the keys that may speak for a peer come from a directory signed by an operator key and carried
  in the project repository; a directory signed by any other key, or older than the one in force, is refused.
- A peer outside the group is confirmed once by a person; a changed key is held with both fingerprints and asked
  again. Seen to fail: a message under a changed key delivered without a person.

**Another machine**

- Two machines on one homeserver run one project: a task on each writes to the other, and each message arrives
  byte for byte (hashed in `outbox/new/` and again at the far end), its signature verified against the sending
  machine's listed key. Seen to fail: a message signed by the other machine's unlisted key delivered rather than
  held.
- A message retried by the transport between machines is delivered once.

## To be checked

- **Whether a group is closed for good or only held** (the operator's). The criteria say held, released and closed;
  `Moderation` knows held and released. Closed could be removing the group from `project.yml`, which needs nothing
  new.
- **Whether a confirmed key is the account's or the project's** (Agent Core's). The account's `allowed_signers` is
  per machine and survives the project; a key in the project repository is signed and travels to every machine.
