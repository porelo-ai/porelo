# B220 — Build-Tool Plugins Found At Run Time

**Status:** Review.
**Size:** M - a new contract module copied from `builds/api` (about 1,200 lines there), a stub, discovery of every
installed plugin, the isolation test, a doc page; no change to an existing contract.

**What must be true.** A plugin for one build tool - Maven, Gradle, npm, pip, Flutter - is a native executable of its
own, installed from its own package, that `porelo` finds at run time and asks over a varlink contract
(`Ecosystem1`: `detect` and `configure`), as it finds agents and build readers; `porelo` names no
build tool. Its parts: the contract in
`ecosystems/api`, its discovery and a stub the tests drive.

## Plan

Read in the code on 2026-10-20. `builds/` is the model, one for one.

1. **`ecosystems/` beside `builds/`** in the root's modules: `ecosystems/api` (`porelo-ecosystem-api`, published to
   Central like `porelo-build-api`, in the BOM) and `ecosystems/stub` (`porelo-ecosystem-stub`, a native image, never
   published, in `porelo.cpu.images`). Each with a `README.md`.
2. **The contract**, `org.fuin.porelo.Ecosystem1.varlink` in the api's resources:
   - `Describe() -> (protocolVersion, ecosystem)`;
   - `Detect(directory, home) -> (findings []Finding, unplaced []Unplaced)`, each finding
     `{part: TOOLS | RULES, key, value, file, line, origin: YOURS | CHECKOUT, unknown, note}`, a credential named by
     its id only;
   - `Configure(repositories []Repository) -> (files []File)`, a repository being [B219](README.md#b219)'s name and
     its URL inside the task, a file `{path, content, ownerOnly}` relative to the task user's home; no directory,
     since nothing reads a task's workspace from the host, so a plugin writes its tool's settings whenever a
     repository is declared, and writes nothing itself.
   - Errors: `NotRecognized`, `Unreadable(detail)`.
   - `EcosystemMain` (`describe`, `serve <socket>`), `EcosystemServer`, `EcosystemProtocol`, `InstalledEcosystem`
     copied from `BuildMain`, `BuildServer`, `BuildProtocol`, `InstalledBuildReader`; the version rule is
     `AgentProtocol`'s from the start, so a plugin never has to release in step with `porelo`.
3. **Discovery of all of them**, unlike a build reader found by name: `InstalledEcosystems.all(dirs)` lists
   `$XDG_DATA_HOME/porelo/ecosystems` then `/usr/libexec/porelo/ecosystems` (`PoreloPaths.packaged()`), each
   executable regular file a name matching `[a-z][a-z0-9-]{0,62}`, the first directory winning per name, as
   `AgentDirectory` does; `Describe`'s `ecosystem` must equal the file's name.
4. **Started normally**: on the machine where the developer starts Porelo, as the person's own installed package. A plugin
   opens no connection and runs no code of the project, by the contract's words; nothing enforces it, by the operator's
   decision of 2026-11-02.
5. **The isolation test**: an ArchUnit rule in `AgentIsolationTest`'s manner that nothing outside `ecosystem..`
   depends on anything but `ecosystem.api`, and a literal scan of main code for the stub's name.
6. **The stub** answers from `answers.json` beside it, as the build stub does; `ci/leg-install.sh`, `Leg` and
   `Deploy.installReaders` install it into `$XDG_DATA_HOME/porelo/ecosystems/stub-ecosystem`.
7. **Docs**: `doc/ecosystem-plugins.md` (writing one, the contract, opening no connection, never running the project's code),
   in `mkdocs.yml`; `README.md` links `ecosystems/README.md`.

**Red first:** `InstalledEcosystemsTest` with a fake plugin main, as `InstalledBuildReaderTest` has one: two
directories holding the same name, where the user's wins; and a plugin whose `Describe` names another ecosystem, refused.

## Acceptance

- With the stub installed, `porelo` lists it and a `Detect` over a directory returns the stub's findings with their
  files and lines.
- `AgentIsolationTest`'s new rule fails on a class outside `ecosystems/` that names the stub.
