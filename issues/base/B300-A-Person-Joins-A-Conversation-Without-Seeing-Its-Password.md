# B300 — A Person Joins A Conversation Without Seeing Its Password

**Status:** Analyzed; accepted on the operator's word on 2026-10-27, with the room's command line in
porelo-message-matrix.
**Size:** S - one option on `talk join` in `app-messaging`, the vault write it makes, its tests and doc rows; the
transport contract grows by `join --token`, written with porelo-message-matrix.

**What must be true.** `porelo talk join <project> <person> --into-vault <entry>` makes the person's account in the
project's conversation and keeps the person's access token, which the transport answers, in the account's vault
under `<entry>`, printing only that it did. The password reaches no stream, log or file, so an agent can join a room through its own shell,
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
2. **What is kept:** with the option, porelo asks the transport's `join` with `--token`, the verb's form that
   answers the person's access token in `secrets` and no password anywhere, logs out every earlier session of the
   person, and takes a display name (porelo-message-matrix
   [MX41](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx41)). That token is written
   as the value of `<entry>`, type `token`, in one `VaultFile.update`, so `porelo vault credential --entry <entry>
   get` hands it on a pipe to the room's command line, which needs nothing else from porelo. `shown` is neither
   printed nor kept. The output is `joined    <person>, login kept in the vault as '<entry>'`. With `--reset` the
   entry is replaced.
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

## To be checked

- The name of the token's key in `join --token`'s `secrets`, with Agent Matrix when the verb is written; and whether
  porelo's generic keeping of a transport's `secrets` must leave that one out, so the token is kept once.
