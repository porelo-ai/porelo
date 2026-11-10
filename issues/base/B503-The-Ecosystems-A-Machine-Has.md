# B503 — The Ecosystems A Machine Has

```json
{
  "$schema": "issue-1.json",
  "id": "B503",
  "title": "The Ecosystems A Machine Has",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 63,
  "blockedBy": [],
  "covers": "`Ecosystems` lists the installed ecosystem plugins and what each places for a repository; its shape agreed with the ecosystems' repository first."
}
```

**Why this size.** a method in the agents' contract part (`40-agent`), read through `porelo-ecosystem-api`; a contract with
the ecosystems' repository, agreed first.

**What must be true.** A client of the daemon lists the ecosystem plugins installed on this machine and, for a
repository, what each would place for it at a first start, as the first start finds them.

## Plan

- **The adapter's version, first, in `porelo-ecosystem-api`**, so the ecosystems' repository builds against it:
  `Ecosystem.version()` (default `""`), sent by `describe` and `Describe` as an optional `version` that is left out
  when blank (`EcosystemServer.description`, used by `EcosystemMain` too); `Ecosystem1`'s `Describe` gains
  `version: ?string` and `EcosystemProtocol.VERSION` goes to 3. `InstalledEcosystem.handshake` reads it, refuses one
  that is no string, makes it plain, and answers it with the protocol version it already read;
  `InstalledEcosystems.Description` carries both as `version` and `protocolVersion`, null for a refused adapter, with
  a three-argument constructor kept for callers built before them.
- **`Ecosystems(checkout?)` in `40-agent.varlink`**, registered in `AgentMethods` and answered by
  `FirstStart.ecosystems`: the plugins as `installed()` finds them for a first start, and for a checkout each usable
  one asked by `ask()`, the one Detect call and refusal handling `FirstStart.detected` now uses too. Nothing is looked
  up (no `Resolve`) and nothing is written. The answer per adapter is `EcosystemPlacing`: outcome, findings, unplaced,
  files by `path` and `ownerOnly` only, and the first start's note. A relative or non-checkout directory is
  `FirstStartRefused` with `NOT_A_CHECKOUT`; an unlistable directory `EcosystemsNotListed`.
- **`files` is empty today**: nothing in Porelo calls an adapter's `Configure` yet, so a first start places no
  settings file; the field is the place a start's call fills when a project declares its tool's repositories.
- Red first: `InstalledEcosystemsTest`, `EcosystemMainTest`, `EcosystemServerTest` and `ContractTest` for the
  version; `FirstStartEcosystemsTest` with a stand-in detector, and `EcosystemsMethodTest` with a stand-in plugin over
  the real protocol, including that a settings file's content never reaches the answer.

## Acceptance

- The installed plugins and what each places are answered as a first start finds them.

## To be checked

- With the ecosystems' repository: that the agreed `repository` is this absolute `checkout` path, since an adapter
  reads a directory and a placed project's repository has none on the host but a person's checkout.
