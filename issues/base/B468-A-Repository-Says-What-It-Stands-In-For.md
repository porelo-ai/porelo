# B468 — A Repository Says What It Stands In For

**Status:** Analyzed.
**Size:** S - one record, the interface description, the stub, the adapter document and their tests in `ecosystems/`;
a contract other repositories read, grown compatibly.

**What must be true.** An ecosystem adapter asked to `Configure` a repository learns what that repository stands in for,
such as every repository (`*`), so the settings it writes send a tool's every request to a repository manager, not only
the requests for that repository's own name.

## Plan

- `org.fuin.porelo.Ecosystem1` grows: `type Repository (name: string, url: string, mirrorOf: ?string)`. Absent or empty
  means the repository stands for itself alone, as today; an adapter built before ignores the field, and a caller built
  before sends none.
- `Repository` in `ecosystems/api` gains `mirrorOf`, with a two-argument constructor for itself alone. The record refuses
  a value outside `[A-Za-z0-9_.:*!,-]`, so only a list of names, `*`, `external:*` or `!name` crosses the wire; the wire
  leaves an empty one out and reads an absent one as empty.
- The stub answers `configure` from `answers.json` as today and passes nothing new; `doc/ecosystem-adapters.md` says
  what the field means, and what the rule findings `credential.<id>` and `credential-kind.<id>` (`basic`,
  `header:<Name>`, `unsupported`) mean beside it.
- **Red first:** in `EcosystemServerTest` or `InstalledEcosystemTest`, a repository with `mirrorOf` `*` arrives at the
  adapter with it, where today the field is lost; in `ContractTest`, the description declares the field; a value with a
  space or `<` is refused by the record.

## Acceptance

- A repository with `mirrorOf` `*` survives the call to an adapter process and back; one without it arrives as today.
- An adapter built against the current contract answers a call that carries the field.
- A value that could make markup is refused before it is sent.
