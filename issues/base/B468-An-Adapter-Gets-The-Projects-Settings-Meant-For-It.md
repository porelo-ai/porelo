# B468 — An Adapter Gets The Project's Settings Meant For It

**Status:** Review.
**Size:** S - one record, one optional parameter, the interface description, the adapter document and their tests in
`ecosystems/`; a contract other repositories read, grown compatibly.

**What must be true.** An ecosystem adapter asked to `Configure` receives the settings the project keeps for that adapter
alone, such as that one repository stands in for every other, unchanged and unread by Porelo, so a tool's peculiarity
needs no field of its own in the contract. Nothing in those settings widens what a task reaches.

## Plan

- `org.fuin.porelo.Ecosystem1` grows: `method Configure(repositories: []Repository, extension: ?object)`. The parameter
  is absent when the project keeps nothing for the adapter; an adapter built before reads only the parameters it knows.
- A record `Extension` in `ecosystems/api` holds an immutable map of plain JSON values - text, a truth value, a whole or
  decimal number, a list, a map, never null - and refuses one over 16 KiB as compact JSON, deeper than 8 or with a blank
  key, naming the limit, never a value.
- `Ecosystem` gains a default `configure(repositories, extension)` that calls today's `configure(repositories)`, so an
  adapter built before compiles and answers as it does; `InstalledEcosystem.configure(repositories, extension)` sends
  the parameter only when the extension holds something.
- `doc/ecosystem-adapters.md` says what the parameter is, that Porelo never reads it, and the finding conventions a
  draft turns into project settings: a `Detect` finding keyed `extension.<key>` with a text value becomes
  `extensions.<adapter>.<key>`, the key's dots nesting, and one keyed `repository.<name>` with a URL becomes a tool
  repository of that name.
- **Red first:** an extension `{"mirrorOf": {"corp": "*"}}` reaches a fake adapter through a real `Configure` call, where today
  there is no way to send it; an adapter that implements only the old method still answers; a block over the size or
  depth is refused; `ContractTest` holds the parameter in the description.

## Acceptance

- An extension survives the call to an adapter process unchanged; a call without one reaches an adapter as today.
- An adapter built against the contract before this change answers a call that carries one.
- A block over 16 KiB or deeper than 8 is refused before it is sent, naming the limit.
