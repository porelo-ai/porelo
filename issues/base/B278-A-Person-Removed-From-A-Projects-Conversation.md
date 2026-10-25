# B278 — A Person Removed From A Project's Conversation

**Status:** Analyzed.
**Size:** M - a new lifecycle verb in the transport contract, which `porelo-message-matrix` implements, two `talk`
commands, a daemon method and error, their tests and the contract's and the commands' pages.

**What must be true.** Whoever runs a machine sees who has an account in a project's conversation and can remove
one: the account is deactivated and out of the room, it is no longer listed, and its messages are held as anyone's
who never joined.

## Why

From the operator's rented-host test of sprint 2d (finding 11, decided (a)): every *Join* on the messages page makes
another account in the project's room, and nothing shows or removes them. The daemon already answers
`MessageMembers` from the list `join` keeps; nothing takes a person out again.

## Plan

Read in `TalkJoinCommand`, `Conversations`, `TaskConversations` (`join`, `members`, `membersFile`),
`TransportLifecycle` (`join`), `TransportDescription.offers`, `MessagingMethods` and `45-messaging.varlink`.

1. **The contract** (`doc/transports.md`, the lifecycle table): `part --project P --person N`, run on `porelo talk
   remove`, given the account's and the project's secrets, prints `removed` (the account) or `{}` when it was gone
   already; `--loopback-only` and `--machine` as for `join`. A transport that does not list `part` in its description
   is never run, and the removal is refused (69) naming the transport. Agreed with `porelo-message-matrix` before
   building.
2. **`TransportLifecycle.part`** beside `join`, the same environment and settings on stdin; **`TaskConversations.part`**
   runs it for a listed person, then writes the list without them, atomically (a temporary file moved over the old one
   with `ATOMIC_MOVE`), so a failed write never leaves half a list.
3. **`porelo talk members PROJECT`** prints `PERSON  ACCOUNT` rows from `Conversations.members`, or `nobody has joined
   <project>`, exit 0; a project with no conversation is refused (64) as `join` refuses it.
4. **`porelo talk remove PROJECT PERSON`** prints `removed   <person> (<account>)`; a person not listed is refused
   (64) naming `porelo talk members`; a transport refusal is its own words with its code mapped as `join` maps it.
   The transport runs first and the list changes only after it succeeded, so a failed removal leaves the person
   listed and can be run again.
5. **Daemon:** `RemoveMessageMember(project, person) -> (user)` and `error NoSuchMember(person)` in
   `45-messaging.varlink`, registered in `MessagingMethods`, answered by the same `Conversations` call.
6. **What their messages do afterwards:** the list also makes each joined person a peer a task may address
   (`MessageWatch.withSiblings`), so a removed person is no longer addressable; their incoming lines must be held as
   from anyone who never joined, held by a test.
7. **`doc/`**: `doc/commands.md`'s `talk` table, `doc/messages.md`'s line on joining, the decision on how a person
   joins.

**Red first:** `TransportConversationsTest.aRemovedPersonIsPartedByTheTransportAndNoLongerListed` (a scripted transport
records `part --project P --person N`; the list then lacks them; neither exists today), and
`...aRefusedPartLeavesThePersonListed`.

## Acceptance

- After `talk remove`, `talk members` and `MessageMembers` no longer list the person, and the transport was run with
  `part` and the person's name, seen in a scripted transport's record.
- A transport refusal, a person not listed, and a transport without `part` each refuse without changing the list.
- A line from the removed account is held, not delivered, held by a test.
- `InterfaceDescriptionTest` holds the method and the error.

## To be checked

- With `porelo-message-matrix`: whether its `part` deactivates the account and kicks it in one call, and what it
  prints for an account already gone.
- Where a person's incoming line is checked against who joined - in Porelo against this list, or only in the
  transport - and so what step 6 changes.
- The end-to-end run against a real homeserver is `porelo-message-matrix`'s once its `part` ships; here the
  scripted transport of the unit tests stands in.
