# B304 — A Machine Lets Another Into Its Conversation

**Status:** Review.
**Size:** M - a transport contract change (two lifecycle verbs) agreed with porelo-message-matrix, two commands and
two daemon methods for an interface, three fixes to what every verb shares, and the docs;
porelo-message-matrix builds its side in [MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42).

**What must be true.** A person on the machine whose server holds a project's conversation lets another machine
into it, and takes it out again, through the transport; the other machine gets what it needs once, its secret never
on a terminal or in a log.

## Why

Decided for porelo-message-matrix
[MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42): the conversation is on
one machine's own homeserver, and a second machine - porelo-host's rented host - must be let in there. Until now a
person let a machine in from their own client only.
Read in porelo at f4a0af45: Porelo admits nobody (`doc/decisions.md`, "A machine on a shared server is admitted by a
person").

## Plan

Read in porelo at 6fd3f518: `doc/transports.md` (the lifecycle verbs, `describe`, `--loopback-only`, `--machine`),
`TransportLifecycle` (the project's settings on standard input, `:716`; `--machine` added to every verb but
`settings` and `part`, `:703`; a failure's cause redacted with the secrets handed in, `:719` and `:733`, but its
message, `:722`, not), `TalkJoinCommand` and `JoinMessages` (a secret answered once), `TalkCommand`,
`MessagingMethods` and `45-messaging.varlink`, and `TaskConversations` (an offline setup checked against loopback only
before a task enrolls).

1. **`admit` and `dismiss` (porelo-message-matrix QM39).** Two lifecycle verbs, listed in `lifecycle`, given the
   account's and the project's secrets in the environment as `join` is, with the project's settings on standard
   input. `admit --project P --machine M` lets machine `M` into the project's conversation on this machine's
   homeserver; `dismiss` takes it out. Porelo passes the admitted machine's name as `--machine` and adds no
   `--machine` of its own (as for `part`). A machine's name that reads as an option, or is not one, is refused before
   the transport runs. The answer splits what is secret from what is not: `admission` (`machine`, `homeserver`,
   `room`, `ca_certificate`) and `secrets` (`registration_token`).
2. **Run as** `porelo talk admit PROJECT MACHINE --into FILE`, which prints no secret, `porelo talk dismiss PROJECT
   MACHINE`, and the daemon's `AdmitMachine` (answered once, as `JoinMessages` answers a login) and `DismissMachine`.
   Both are refused for an offline project, whose messages never leave the machine.
3. **Three fixes every verb shares:**
   - **a failure's message is redacted** as its cause already is, cleaned of escape sequences first, and the
     secrets a verb prints on its standard output before failing (`secrets`, `account`, `login`) join those it was
     given;
   - **an offline project's `setup` is checked against loopback after every `setup`**, not only at a task's enroll,
     so a setup through the daemon or a join that reaches away is refused before anything is kept: no room is
     remembered and no secret reaches the vault, which would start polling;
   - **`--into` is checked before the transport mints a token** - an existing file, a directory the person cannot
     write, or a name the file system refuses (tried once by linking a file under it) is refused - and the
     admission is written whole, owner-only from the start, beside it and linked in under its
     name, which fails rather than replace a file that appeared meanwhile.

   A fourth, `VaultClearing` walking every conversation, mattered only with a machine's own settings, which name a
   server for a scheme the project file leaves empty; it went with them to [B309](README.md#b309).
4. **Red first:** `MachineTransportsTest` (`admit` with the admitted machine's `--machine` only; a token the fake
   transport prints absent from the terminal and in a new `rw-------` file; `--into` refusing an existing file and a
   missing directory before the transport runs; a refusal's message holding no secret; an offline setup reaching
   away refused and nothing remembered; a machine's name read as an option refused; an offline project admitting
   nobody); `MessagingMethodsTest` (the admission and its secret apart on the wire; a bad name a named refusal);
   `InterfaceDescriptionTest` holds the new methods to `45-messaging.varlink`.
5. **Read against the skills, and what came of it:** `dismiss` is refused for an offline project; the machine's name
   must not read as an option; `--into` is checked before the transport mints a token and is linked in whole.
   A transport that could not be run is a refusal like the others, `ConversationRefused` on the wire, saying only
   its cause; `admit` and `dismiss` say they need the vault's secrets, not that they keep any.
   **Declined:** a secret a failing `admit` prints only on its standard error cannot be told from other words; the
   contract already forbids it there. A secret shorter than eight characters is not withheld by its value, as
   before, since a short word would be withheld everywhere it occurs. Standard output that is not one JSON object,
   or what a verb printed before it timed out, is not read for secrets: the contract asks for one object, and
   neither is shown. The loopback check that remains in a task's enroll stays as a second guard.
6. **Docs:** `doc/transports.md` (the two verbs), `doc/commands.md` (`talk admit`, `talk dismiss`),
   `doc/decisions.md` (who admits a machine; the token in a file), the changelog.

## Decided

- **The signed project file names the server; a machine sets none of it** (operator, 2026-10-27, variant (a)),
  superseding QB74: porelo-project's signed `project.yml` names the homeserver `https://matrix.porelo.internal:<port>`,
  the room, the CA's text as `ca_certificate` (QM43) and a `registration_token_file`, and every machine reaches that name. A machine's
  own transport settings, built at `baa7e433`, were taken out again and are the Idea
  [B309](README.md#b309).
- **`talk admit --into FILE` may write the registration token in the clear to a new owner-only file** (QB75, operator,
  2026-10-27), for a person without an interface; the interface always takes it from `AdmitMachine` straight into
  `secrets.kdbx`. The accepted risk is in `doc/decisions.md`.

**What [PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78) changes, nothing
reworked yet:** `talk admit` and `talk dismiss` serve an account's own homeserver, the laptop's of the move
to a host, and are not the model of that plan: on a prepared host each person registers with a token of their own and the
project admin's Porelo keeps the room's machines to the signed list ([B314](README.md#b314)). Under
[B310](README.md#b310) a project naming an access file gives them its transport settings from that file.
`doc/commands.md` already says that `dismiss` takes the machine out of every project on the server.

## Acceptance

- On porelo-host's rented host, admitted from this machine: its task writes in the project's room on this machine's
  homeserver and a local agent answers; `dismiss` refuses the host's token at once.
- `talk admit` prints no secret and writes the admission to a new file readable by its owner only.
- Seen to fail first: today a transport is never asked to let a machine in.

## To be checked

- The transport's side is agreed with porelo-message-matrix (`admit`'s `admission`/`secrets` split, `dismiss`'s
  `removed`) and built in its [MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42).
