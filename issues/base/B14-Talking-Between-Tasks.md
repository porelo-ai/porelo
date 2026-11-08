# B14 — Talking Between Tasks

**Status:** Backlog; blocked by porelo [B86](README.md#b86). Built: talking between tasks on one machine. Waiting:
groups, trust confirmed once with fingerprints, and two machines on one
homeserver, the three pieces the plan splits.
**Size:** XL - groups, trust confirmed once and two machines are three pieces; the plan splits them.

**What must be true.** A person can address a group of peers as one - held, released and closed as one - and a
task's message reaches a task of the same project on another machine with the same guarantees it has on one:
signed on the host, filtered, attributed against a key list, delivered once.

**Closing a group, decided 2026-10-11:** while a group is closed, every message to it is refused at once with the
reason "group closed", and nothing is queued. That is what sets it apart from holding, which keeps the message
until it is released. A group is closed and reopened at any time, without a commit.

Built and measured on one machine, over a transport that keeps a conversation ([B86](README.md#b86)): the mailbox, the signature,
the sluice both ways, dispatch, delivery against a key list, the record, duplicate suppression, bounces, budgets,
holding and releasing with the four modes, `porelo talk`, and the daemon's message methods. A message addressing
more than one peer is refused with a reason today. How groups would be built is
[B14-Talking-Between-Tasks_design.md](B14-Talking-Between-Tasks_design.md).

## Plan

Read in the code on 2026-11-04.

**What is there.** The messaging lives in `apps/app-messaging/src/main/java/org/fuin/porelo/app/`:
`MessageIntake`, `MessageFiltering`, `MessageDispatch`, `MessageDelivery`, `Moderation`, `MessageSigners`;
the daemon's methods are `daemon/src/main/java/org/fuin/porelo/daemon/MessagingMethods.java` with
`wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/45-messaging.varlink`.
`MessageDispatch.addressees` already reads `metadata.to` as a string or a list, and a list of more than one is refused.
`Moderation` holds or releases one peer for the whole project (`Moderate(project, name, held)`); the mode is set
in `project.yml` (`mail.rules`, a peer's `mode`). The project file has `mail.peers.<peer>`
(`address`, `trust`, `per_day`, `mode`) in `ProjectSchema` and no `groups`.

**Task certificates are built.** `MessageIntake` signs with the task's own key, held in the vault on the host;
`MessageSigners` checks its certificate's task principal, certifier and validity at arrival. `ProjectMembers`
(`apps/app-model/src/main/java/org/fuin/porelo/app/ProjectMembers.java`) reads current organization project keys
as certifiers; messaging reads no `machine-signers` or `allowed_signers`, and `project follow` is gone.
Decided 2026-11-04: the task certificate from a current certifier replaces the operator-signed peer-key directory
and the listed machine key.

Whether an older or forked organization state is refused for a project's certifiers, read on 2026-11-04, not run:
`MessageSigners.certifiers` reads them from `OrganizationProjects` (`ProjectMembers.current`). Off a host a start writes
them after `IdentityOrganization.read`, whose `GitTreeStore` keeps a trust record and refuses a tip that does not
continue it (`refused-state`); a stale reading starts nothing (`ProjectTaking.fromTheOrganization`). On a host, root's
`org fetch --since` does the same and writes the host's file. So the refusal holds where the state is read, with two
findings:

- **Off a host the certifiers are not read again between starts**: a key revoked in the organization stays a believed
  certifier until the project's next start (`OrganizationProjects`, its class comment). No old state is served; the
  running task simply does not look.
- **A first read has nothing to compare with**: no trust record, no earlier commit. Off a host it starts from the
  founder's key on the forge, on a host from the pin.

**Not built: trust confirmed once.** `Release(task, id, refuse, reason)` has no way to trust a key.
The held reason can name the signing key's fingerprint, but no confirmation records trust for that peer.

The split, each an issue of its own when it is started:

1. **Groups (M).** `mail.groups.<group>: [peer, ...]` in `ProjectSchema`, `ProjectReader` and `Mail`; a group name
   in `metadata.to` resolves in `MessageDispatch` to its members, each queued over its own transport, the sender's
   receipt naming each; `Moderation` takes a group name and holds or releases all its members' traffic, and
   `Moderate` and `porelo talk hold` accept it. Closing is a state of its own beside held: `Moderation` keeps it with
   the group's other control state, on the host and not in `project.yml`, so closing and reopening need no commit;
   `Moderate` grows an optional `closed`, and `porelo talk` a way to close and reopen. A message to a closed group
   is refused in `MessageDispatch` before anything is queued, its sender told "group closed". Red first: a test in
   `MessageDispatchTest` sending to a two-member group, red while it is refused as addressing more than one peer;
   then one holding the group and asserting on what reaches each member's inbox; then one closing it and asserting
   the refusal and an empty queue, and one reopening it and asserting the next message is delivered.
2. **Trust confirmed once (M).** An arrival signed by a key no list names is held with its fingerprint (as today it
   is held unattributed); `Release` gains `trustKey`, which writes the key into the account's `allowed_signers` for
   that peer; a key that changes for a known peer is held with both fingerprints. Contract: `Release` grows one
   optional field. Red first: a test delivering under a changed key without a person.
3. **Another machine (L, blocked by [B86](README.md#b86)).** Nothing new in the mailbox: the transport carries signed pairs. What is
   left is the two-machine scenario on one homeserver - a task on each writes to the other, the bytes hashed in
   `outbox/new/` and again in the far inbox, a message signed by a key no current certifier certified held - and a retry
   by the transport delivered once (`MessageDelivery`'s duplicate suppression, measured across machines).

## Acceptance

**Groups**

- A group is a list of peers on the host; a message to it reaches every member over whatever transport each uses.
  Seen to fail: a member missing the message, or a message to a group still refused as addressing more than one
  peer.
- A group can be held, released and closed while it runs; a held message reaches no reader and its sender is
  told. Seen to fail: a member reading a message sent while the group was held.
- A message to a closed group is refused at once with the reason "group closed", and nothing is queued; reopening
  the group takes no commit, and the next message is delivered. Seen to fail: a message sent while the group was
  closed is queued or delivered after it reopens, or closing or reopening changes `project.yml`.
- Inside a group, a message is checked against the task certificate from a current certifier of the project; one
  signed by a key that no current certifier certified, or whose certificate is not valid at arrival, is held.
- An older or forked organization state is refused, so a project's certifiers cannot go back to a state before a
  revocation. Seen to fail: an older state served again changing whom a task believes.
- A peer outside the group is confirmed once by a person; a changed key is held with both fingerprints and asked
  again. Seen to fail: a message under a changed key delivered without a person.

**Another machine**

- Two machines on one homeserver run one project: a task on each writes to the other, and each message arrives
  byte for byte (hashed in `outbox/new/` and again at the far end), its signature checked against the sending
  task's certificate. Seen to fail: a message signed by a key no current certifier certified delivered rather than
  held.
- A message retried by the transport between machines is delivered once.

## To be checked

- **Whether a confirmed key is the account's or the project's** The proposed account's `allowed_signers`
  would be per machine and survive the project; a key in the project repository would be signed and travel to
  every machine.
- **Where the latency of a direct message from a person to a task goes** Read the transport's and the daemon's
  journal lines for one direct person-to-task delivery to see where the three-second delay is spent and whether a
  small change removes it.
