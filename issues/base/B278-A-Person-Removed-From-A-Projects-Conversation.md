# B278 — A Person Removed From A Project's Conversation

**Status:** Review: published since `0.5.1~snapshot.329`; green on the VM in its acceptance suite.
**Size:** M - a new lifecycle verb in the transport contract, two `talk`
commands, a daemon method and error, their tests and the contract's and the commands' pages.

**What must be true.** Whoever runs a machine sees who has an account in a project's conversation and can remove
one: they leave the room and the members list, and their room messages no longer reach tasks. The transport
says whether their account was removed, kept for a reason, or already gone.

## Why

From the operator's rented-host test of sprint 2d (finding 11, decided (a)): every *Join* on the messages page makes
another account in the project's room, and nothing shows or removes them. The daemon already answers
`MessageMembers` from the list `join` keeps; nothing takes a person out again.

## Plan

Read in `TalkJoinCommand`, `Conversations`, `TaskConversations` (`join`, `members`, `membersFile`),
`TransportLifecycle` (`join`), `TransportDescription.offers`, `MessagingMethods` and `45-messaging.varlink`.

1. **The contract** (`doc/transports.md`, the lifecycle table): `part --project P --person N`, run on `porelo talk
   remove`, given the account's and the project's secrets in the environment and settings JSON on stdin,
   prints `{"removed":"<account>"}`, `{"kept":"<account>","why":"<words>"}` or `{}` when it was gone
   already; `--loopback-only` as for `join`, and never `--machine`. A transport that does not list `part` in its description
   is never asked to part, and removal is refused (69) naming the transport. Exit 75 is temporary, 77 refused,
   78 configuration missing; refusals carry the transport's own words, as join does.
2. **`TransportLifecycle.part`** beside `join`, the same environment and settings on stdin; **`TaskConversations.part`**
   runs it for a listed person, then writes the list without them, atomically (a temporary file moved over the old one
   with `ATOMIC_MOVE` and `REPLACE_EXISTING` in the same directory), so a failed write never leaves half a
   list. Join and part share a lock while updating the list, so overlapping updates cannot restore a removed
   member. Public `Conversations.remove` is the daemon's entry, with a distinct `Refused.missing` for an
   unlisted person.
3. **`porelo talk members PROJECT`** prints `PERSON  ACCOUNT` rows from `Conversations.members`, or `nobody has joined
   <project>`, exit 0; a project with no conversation is refused (64) as `join` refuses it.
4. **`porelo talk remove PROJECT PERSON`** prints `removed   <person> (<account>)`, or
   `removed   <person> from <project>; their account <account> stays: <why>` for kept, or
   `removed   <person> (nothing was left in the room)` for `{}`; a person not listed is refused
   (64) naming `porelo talk members`; a transport refusal is its own words with its code mapped as `join` maps it.
   The transport runs first and the list changes only after it succeeded, so a failed removal leaves the person
   listed and can be run again.
5. **Daemon:** `RemoveMessageMember(project, person) -> (user, kept, why)` and `error NoSuchMember(person)` in
   `45-messaging.varlink`, registered in `MessagingMethods`, answered by the same `Conversations` call.
6. **What their messages do afterwards:** the list also makes each joined person a peer a task may address
   (`MessageWatch.withSiblings`), so a removed person is no longer addressable. Porelo checks person files'
   `sender` against this list in `PersonsWords.read`, called by `TransportConversations.handOut` with
   `TransportLifecycle.people`. Unknown senders are dropped before delivery, as anyone who never joined;
   the regression delivers a line before removal and rejects the next one after it. No delivery code changes.
7. **`doc/`**: `doc/commands.md`'s `talk` table, `doc/messages.md`'s line on joining, the decision on how a person
   joins. The transport owner's end-to-end run against its server follows its part release; here a scripted
   transport stands in, with no container runtime or server.

**Red first:** `TransportConversationsTest.aRemovedPersonIsPartedByTheTransportAndNoLongerListed` (a scripted transport
records `part --project P --person N`; the list then lacks them; neither exists today), and
`...aRefusedPartLeavesThePersonListed`.

## Acceptance

- After `talk remove`, `talk members` and `MessageMembers` no longer list the person, and the transport was run with
  `part` and the person's name, seen in a scripted transport's record.
- A transport refusal, a person not listed, and a transport without `part` each refuse without changing the list.
- A line from the removed account is dropped before delivery, held by a test.
- `InterfaceDescriptionTest` holds the method and the error.
- Known limit, the transport's: after a `kept` answer the account is still in another project's room, so a later
  `talk join` of the same person to this project is refused there as for any person of another project.
