# B474 — A Task Fetches Its Project's Snapshots Through Its Broker

**Status:** Backlog (Estimated, M, 180 minutes, on the operator's word, 2026-11-06).
**Size:** M - a package route in the task's broker, the task's own forge token read for it, the start ordered for it,
and the settings through `porelo.yml` and Configure; their tests. Estimate 180 minutes, once
[B470](README.md#b470) and [B467](README.md#b467) have landed.

**What must be true.** A task's build resolves its project's own snapshots from the host's forge registry, by the
repository name its `porelo.yml` declares, through Porelo's broker: `GET` and `HEAD` below
`/api/packages/<organization>/maven` of the task's own project and nothing else, with the host-issued token never in
the container. The project's plan holds the whole; this is porelo's part, and the host's part is porelo-host's: the
task token it issues gains `read:package`, with its Molecule check.

## Found

Read in the code on 2026-11-06 (a Codex reading, checked line by line):

- **The task's forge token** comes from the host's helper at the start (`HostPerson`, `sudo -n ... task <task>`), is
  kept on the host (`TaskSecrets.keepForgeToken`), and serves only the gate's git forwarding
  (`GateServeCommand`, `secrets.forgeToken(container)`). Nothing hands it to a package fetch.
- **The broker for further credentials** exists: `CredentialWiring` mints a phantom token per credential and its relay
  runs in the task's namespace; no stored value enters the container.
- **The broker's route checks host and port, not the path:** `VaultProxy` builds `route.upstream() + target` and
  refuses only another host, port or user part. A package route needs a path boundary.
- **The broker answers JSON and event streams only** (`VaultProxy`: `application/json`, `text/event-stream`); a POM,
  metadata, a checksum or a jar is withheld.
- **The task's own tokens are kept out of the credential listing on purpose** (`PoreloContext`, "A task's own tokens
  are not credentials"), so the broker cannot read the forge token through the general view.
- **The start order:** `TaskLaunch` starts the broker (`wiring().startVault`) before the host helper issues the token.
- **Configure** carries repositories and the adapter's extension already ([B468](README.md#b468)); a repository's
  `porelo.yml` declaring a tool repository and a credential by name and kind is [B470](README.md#b470)/[B467](README.md#b467)'s.

## Plan

1. **A package route** in the broker for the task's own project: `GET` and `HEAD` only, the normalized path below
   `/api/packages/<organization>/maven/`, refused before any authenticated request for another organization, another
   API path, a sibling prefix, traversal (also encoded), an absolute target, a redirect or a write.
2. **Maven's answers on that route alone:** POMs, metadata, checksums and jars pass unchanged there; every other route
   keeps refusing what is not JSON or an event stream.
3. **The token, narrowly:** the broker reads this task's host-issued token by a way of its own, not through the
   credential listing; the start obtains and keeps the token before the broker starts, and a failed start still ends
   it.
4. **The settings:** the repository's `porelo.yml` names the tool repository and its credential by name and kind
   ([B470](README.md#b470), [B467](README.md#b467)); Configure writes Maven's settings with the broker's address and a phantom token. A checkout that runs
   with `-s settings.xml` reads the same variable as porelo's own ([B306](README.md#b306)).
- **Red tests:** a package request outside the organization's maven path is refused before upstream; a jar and a POM
  round-trip unchanged; the real token is in neither the container, its environment nor its settings; a failed start
  ends it.
- **Measured only on a forge:** a real task resolving a private snapshot by its declared name (porelo-host's VM with
  Porelo installed, or a rented host).

## Open questions

- Whether removing a task ends its token at once, or its expiry suffices.
- The newest-snapshot rule and the private-coordinate filter: the shared registry design, as for [B306](README.md#b306).
