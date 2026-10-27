# B304 — A Machine Sets Its Own Transport Settings

**Status:** Analyzed.
**Size:** L - crosses repositories: a transport contract change (a `describe` field and two lifecycle verbs) agreed
with porelo-message-matrix, a machine's settings file and its command, one seam for the three places that read a
project's transport settings, daemon methods for an interface, and the docs; porelo-message-matrix builds its side
in [MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42).

**What must be true.** A machine that follows a project may set some of a transport's settings for itself - for
Matrix, the homeserver, the room, the CA certificate and the registration token - in place of what the project file
names, so one project file serves machines that talk through different homeservers; every other setting stays the
project file's.

## Why

Decided for porelo-message-matrix
[MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42): one project file names
one homeserver, and a host's own homeserver is not the desktop's. Rather than every machine naming its homeserver as a
central one, a machine's settings may differ from the project file's.
Read in porelo at f4a0af45: a project's conversation comes from `mail.transports.<name>` in its project file, handed
to the transport as written (`doc/messages.md`), and a machine sets none of it.


## Plan

Read in porelo at 6fd3f518: `doc/transports.md` (the lifecycle verbs, `describe`, `--loopback-only`, `--machine`),
`TransportLifecycle` (the project's settings on standard input, `:716`; `--machine` added to every verb but
`settings` and `part`, `:703`; a failure's cause redacted with the secrets handed in, `:719` and `:733`, but its
message, `:722`, not), `TalkJoinCommand` and
`JoinMessages` (a secret answered once), `TalkCommand`, `MessagingMethods` and `45-messaging.varlink`,
`ProjectFileCheck` (the `settings` check of a project file as written), `TransportConversations` (a project is
polled once its project secrets exist, `:171`), and every reader of a project's transport settings:
`TaskConversations:511` (setup, enroll, retire, join, part), `Clearing:377` and `VaultClearing.elsewhere` (`:268`,
over `transports()` only) read the values; `MessageWatch:1127` reads only which schemes there are.

1. **The transport names what a machine may set.** `describe` gains `machineSettings`, a list of setting names whose
   values are plain strings (porelo-message-matrix: `homeserver`, `room`). Porelo names no Matrix setting and refuses a
   name the transport does not list. **No setting is a path a client names:** a client that can set a file's path
   could have the transport read any file of the account and send it to a homeserver it also set. What a machine needs
   beyond strings Porelo keeps itself:
   - **the CA certificate** is given as its content and kept by Porelo in the project's directory below, handed to the
     transport as `ca_file` pointing there;
   - **a machine's secret** (porelo-message-matrix: its registration token, `PORELO_MATRIX_REGISTRATION_TOKEN`) is
     named by the transport in `describe` as `machineSecrets`, kept in the vault in an entry of its own,
     `transport/<scheme>/machine/<project>`, apart from the project's secrets so it neither starts polling before
     `setup` nor is replaced when `setup` keeps what it printed, and handed to the lifecycle verbs in their
     environment, never as a file.
2. **Where they live and who writes them.** One owner-only JSON file per project and transport, with the CA beside it,
   under the account's configuration (`$XDG_CONFIG_HOME/porelo/transports/<project>/`), located through
   `MessagingPaths` (`app-base`, said in the channel first). Written only by `porelo talk settings PROJECT SCHEME
   [--set NAME=VALUE ...] [--unset NAME ...] [--ca FILE] [--registration-token-from-stdin]` and the daemon's
   `SetTransportSettings`; read by the daemon's `TransportSettings` (the names the transport allows and what is set),
   so an interface can show them; `porelo talk settings PROJECT SCHEME --json` prints the same, whether a CA and a
   secret are set but never the secret, which porelo-host reads to tell whether a host is admitted. The project file
   stays signed and unchanged.
3. **One seam.** `TransportSettings.of(context, project, scheme)` is the project's map, or an empty one when the
   project names the scheme only through a peer, with the machine's values over it key by key. It replaces the reads
   in `TaskConversations` and `Clearing`; `VaultClearing.elsewhere` takes the context and walks
   `mail().conversations()` like the others, so a scheme named only through a peer is cleared too. `ProjectFileCheck`
   keeps the file as written, since it judges a project file for every machine; `talk settings` instead runs the
   transport's `settings` check on the merged map before it saves. Any change to a machine's settings, its CA or its
   secret drops this project's cached conversation state, addresses and members, so the next `setup` starts afresh
   rather than reporting the old room; Porelo compares no setting by name.
4. **Offline stays offline.** For an offline project, `talk settings` runs the `settings` check with
   `--loopback-only`, which the transport then also takes (today only `setup`, `enroll`, `join` and `part` do), and
   `admit` is refused, since an offline project's messages never leave the machine. What `setup` says it reaches is
   checked against loopback after every `setup`, not only at a task's enroll as today.
5. **`admit` and `dismiss` (porelo-message-matrix QM39).** Two lifecycle verbs, listed in `lifecycle`, given the
   account's and the project's secrets in the environment as `join` is. `admit --project P --machine M` lets machine
   `M` into the project's conversation on this machine's homeserver; `dismiss` takes it out. Porelo passes the
   admitted machine's name as `--machine` and adds no `--machine` of its own (as for `part`). The answer splits what
   is secret from what is not: `admission` (`machine`, `homeserver`, `room`, `ca_certificate`) and `secrets`
   (`registration_token`). A failure's message is redacted as its cause already is, and the secrets a verb prints
   join those it was given in redacting both. Run as
   `porelo talk admit PROJECT MACHINE --into FILE`, which creates the file new and owner-only in one step and
   prints no secret, `porelo talk dismiss PROJECT MACHINE`, and the daemon's `AdmitMachine` (answered once, as
   `JoinMessages` answers a login) and `DismissMachine`.
6. **Cleared after the transport.** The machine's settings, CA and secret are removed by `TransportLifecycle.clear`
   with the conversation's state, only once the transport's `clear` has succeeded, so a cleared project still
   reaches the homeserver it was on and a failure can be retried; `project unfollow` keeps them, as it keeps the
   conversation, and `prune` sweeps those of a project this machine no longer knows.
7. **Red first:** `TransportSettingsTest` (a machine's `homeserver` reaches the transport over the project file's; a
   name not in `machineSettings` refused; a project named only through a peer; the cached state, addresses and
   members dropped on any change; the machine's secret apart from the project's, so polling does not start); `TransportDescriptionTest` (`machineSettings` read from `describe`,
   and an empty list when absent refuses every name); `TransportLifecycleTest` (`admit` with the admitted machine's
   `--machine` only; a token the fake transport prints absent from the output and from a failure's stderr; `--into`
   refusing an existing file and creating `rw-------`; a refusal's message holding no secret); `ClearingTest` (the
   settings kept until the transport's `clear` succeeded, then gone);
   `InterfaceDescriptionTest` holds the new methods to `45-messaging.varlink`.
8. **Docs:** `doc/transports.md` (the field, the two verbs, the token as a secret), `doc/commands.md` (`talk
   settings`, `talk admit`, `talk dismiss`), `doc/hosts.md` (where an admitted host's values go), the changelog.

## Acceptance

- On porelo-host's rented host, admitted from this machine: its task writes in the project's room on this machine's
  homeserver and a local agent answers; `dismiss` refuses the host's token at once.
- A machine's `homeserver` reaches the transport over the project file's; a name the transport does not list is refused.
- Seen to fail first: today the project file's homeserver is the only one a transport is given.

## Open questions

- **A machine's settings change where a signed project's messages go.** For a guarded or online project, the
  account's own unsigned file can point the transport at another homeserver than the project file names. (a)
  Recommended: allowed, since only the account writes it, as it writes its own vault; the accepted risk goes into
  `doc/decisions.md`. (b) Only an offline project's or none: a host then needs a project file of its own.
- **The registration token on disk for a person without an interface:** `talk admit --into FILE` writes it in the
  clear to a new owner-only file, as the VPN key lies on the host's disk. (a) Recommended: allowed, with the accepted
  risk recorded; the interface takes it from `AdmitMachine` straight into `secrets.kdbx`. (b) Only the interface may
  admit a machine.

## To be checked

- With Agent Matrix: `machineSecrets` in `describe`, beside the agreed `machineSettings` (`homeserver`, `room`), the
  CA as `ca_file` in Porelo's directory, the token as `PORELO_MATRIX_REGISTRATION_TOKEN`, and `admit`'s
  `admission`/`secrets` split.
