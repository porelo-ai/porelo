# B300 — A Person Joins A Conversation Without Seeing Its Password

**Status:** Analyzed; accepted on the operator's word on 2026-10-27, with the room's command line in
porelo-message-matrix.
**Size:** S - one option on `talk join` in `app-messaging`, the vault write it makes, its tests and a doc row; no
contract changes.

**What must be true.** `porelo talk join <project> <person> --into-vault <entry>` makes the person's account in the
project's conversation and keeps the login the transport answers in the account's vault under `<entry>`, printing
only that it did. The password reaches no stream, log or file, so an agent can join a room through its own shell,
whose output is its transcript.

## Why

The operator moves the agents' channel from a file into the project `porelo`'s Matrix room. Each agent joins as a
person and reads, posts and waits there through a small command line of porelo-message-matrix's, which takes the
login from the vault as a transport takes its secrets. Read in porelo at 0b02c1b7: `talk join` prints the
transport's `shown` text once on standard output, and that text carries the password ("Shown once, never kept").
Run by an agent, that puts a secret into its transcript, against the shared rule that a secret never appears in an
answer.

## Plan

Read in `TalkJoinCommand`, `TaskConversations.join`, `TransportLifecycle.join`, `VaultPutCommand` and
`doc/transports.md`.

1. **`TalkJoinCommand`** gains `--into-vault <entry>`, an entry name checked by the vault's name rule. With it, the
   vault is opened first, as `vault put` opens it, and a shut or absent vault is refused before `join` runs, so no
   account is made whose login nobody keeps.
2. **The login kept:** the transport's `login` object (`join`'s answer for an interface, answered once; see
   `doc/transports.md`) is written as JSON into the entry, type `talk-login`, in one `VaultFile.update`. `shown` is
   neither printed nor kept. The output is `joined    <person>, login kept in the vault as '<entry>'`. With `--reset`
   the entry is replaced by the new login.
3. **Without the option** nothing changes: a person at a terminal still gets the login shown once.
4. **Docs:** the `talk join` row of `doc/commands.md` and the join paragraph of `doc/messages.md`.
5. **Red first:** a `TransportConversationsTest`-style test with a stand-in transport whose `join` answers a login
   with a known password: with `--into-vault` the password is in neither stream and the vault entry holds the whole
   login; with a shut vault the transport's `join` is never run.

## Acceptance

- `porelo talk join porelo agent-core --into-vault talk.agent-core` prints no password, and `porelo vault list` shows
  the entry `talk.agent-core` with its type and no value.
- Run with a shut vault it refuses before any account is made.

## To be checked

- With Agent Matrix: the exact fields of the `login` object (user, password, homeserver address, room) and how its
  command line reads the entry from porelo - through porelo's credential helper on a pipe, or handed by porelo on
  standard input as a transport's secrets are.
