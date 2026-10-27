# B300 — A Person Joins A Conversation Without Seeing Its Password

**Status:** Implementing: rework found in porelo-message-matrix's acceptance, the display name and the room's id;
the transport's `join --token` and `--display` are porelo-message-matrix's.
**Size:** S - one option on `talk join` in `app-messaging`, the vault write it makes, its tests and doc rows; the
transport contract grows by `join --token`, written with porelo-message-matrix.

**What must be true.** `porelo talk join <project> <person> --into-vault <entry>` makes the person's account in the
project's conversation and keeps the person's access token, which the transport answers, in the account's vault
under `<entry>`, printing only that it did and the room's id. The password reaches no stream, log or file, so an agent can join a room
through its own shell, whose output is its transcript. `--display <name>` gives the person the name the room shows, by
which the others address them.

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
2. **What is kept:** with the option, porelo asks the transport's `join` with `--token`, the verb's form that answers
   the person's access token in `secrets` as `PORELO_MATRIX_ACCESS_TOKEN`, that key alone, and no password anywhere,
   logs out every earlier session of the person, and takes a display name (porelo-message-matrix
   [MX41](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx41)). That token is written as
   the value of `<entry>`, type `token`, in one `VaultFile.update`, so `porelo vault credential --entry <entry> get`
   hands it on a pipe to the room's command line, which needs nothing else from porelo. `shown` is neither printed nor
   kept, and nothing of the join is kept generically, as today. The output is `joined <person>, login kept in the vault
   as '<entry>'`. With `--reset` the entry is replaced.
3. **Without the option** nothing changes: a person at a terminal still gets the login shown once.
4. **Docs:** the `talk join` row of `doc/commands.md`, the join paragraph of `doc/messages.md`, and `join
   --token`'s row in `doc/transports.md`, written with porelo-message-matrix.
5. **Red first:** a `TransportConversationsTest`-style test with a stand-in transport whose `join` answers a password
   in `shown` and, with `--token`, a token in `secrets`: with `--into-vault` neither appears in either stream, the
   stand-in was asked with `--token`, and `vault credential --entry` answers the token; with a shut vault `join` is
   never run.

## Acceptance

- `porelo talk join porelo agent-core --into-vault talk.agent-core` prints no password, and `porelo vault list` shows
  the entry `talk.agent-core` with its type and no value.
- Run with a shut vault it refuses before any account is made.

## Built

- `talk join --into-vault <entry>` refuses a reserved name (64), a missing vault and a shut one (69) before the
  transport's `join` runs; then asks `join --token`, keeps the one value of its `secrets` as `<entry>`, type `token`,
  through the opener the vault opened with, and prints only `joined <person>, login kept in the vault as '<entry>'`.
  A transport that answers no single token is refused (69) with the account made and nothing kept, and `--reset`
  asks again.
- `TransportLifecycle.join` and `TaskConversations.join` take the token flag; without it nothing changes.
- Docs: `doc/commands.md`, `doc/messages.md`, and `join --token` in `doc/transports.md`; a changelog entry.
- `--display <name>` is handed to the transport's `join` as `--display`, with or without `--into-vault`, and the
  transport checks it. With `--into-vault` the line names the room from `login.room`: `joined <person> in <room>,
  login kept in the vault as '<entry>'`; a transport that answers no room leaves the line as before.
- A display name starting with `-` is refused (64) before the transport runs, since it would reach the transport's
  `join` as a flag; the room is printed only when it is one token, with no whitespace or control character.
- Red first in `TransportConversationsTest`: `--display` was unknown and the line named no room, then green; the
  refusal of `--display=--token` seen red with the check removed.
- Red first in `TransportConversationsTest`: the option was unknown, then green - the token kept, neither the
  password nor the token in either stream, `--token` asked; a missing vault refused with no `join` run. A shut vault
  is the same path through `openerAsking` and has no test of its own.
