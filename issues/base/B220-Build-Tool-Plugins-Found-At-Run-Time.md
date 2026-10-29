# B220 — Build-Tool Plugins Found At Run Time

**Status:** Analyzed.
**Size:** M - a new contract module copied from `builds/api` (about 1,200 lines there), a stub, discovery of every
installed plugin, a start without network, the isolation test, a doc page; no change to an existing contract.

**What must be true.** A plugin for one build tool - Maven, Gradle, npm, pip, Flutter - is a native executable of its
own, installed from its own package, that `porelo` finds at run time and asks over a varlink contract
(`Ecosystem1`: `detect` and `configure`), as it finds agents and build readers; `porelo` starts it without network
and names no build tool. Its parts: the contract in
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
     [B224](README.md#b224)'s from the start, so a plugin never has to release in step with `porelo`.
3. **Discovery of all of them**, unlike a build reader found by name: `InstalledEcosystems.all(dirs)` lists
   `$XDG_DATA_HOME/porelo/ecosystems` then `/usr/libexec/porelo/ecosystems` (`PoreloPaths.packaged()`), each
   executable regular file a name matching `[a-z][a-z0-9-]{0,62}`, the first directory winning per name, as
   `AgentDirectory` does; `Describe`'s `ecosystem` must equal the file's name.
4. **Started without network**: the command is `unshare --user --map-root-user --net -- <plugin> serve <socket>`, so
   only loopback exists inside; the socket is in a `0700` directory of the caller's. When `unshare` fails, the plugin
   is not started and the refusal names it - never started with network instead. Measured on this development
   machine: inside `unshare --user --map-root-user --net` only `lo` exists and an HTTPS request gets no connection,
   with `kernel.apparmor_restrict_unprivileged_userns=1`.
5. **The isolation test**: an ArchUnit rule in `AgentIsolationTest`'s manner that nothing outside `ecosystem..`
   depends on anything but `ecosystem.api`, and a literal scan of main code for the stub's name.
6. **The stub** answers from `answers.json` beside it, as the build stub does; `ci/leg-install.sh`, `Leg` and
   `Deploy.installReaders` install it into `~/.local/share/porelo/ecosystems/stub-ecosystem`.
7. **Docs**: `doc/ecosystem-plugins.md` (writing one, the contract, no network, never running the project's code),
   in `mkdocs.yml`; `README.md` links `ecosystems/README.md`.

**Red first:** `InstalledEcosystemsTest` with a fake plugin main, as `InstalledBuildReaderTest` has one: two
directories holding the same name, where the user's wins; a plugin whose `Describe` names another ecosystem,
refused; and a plugin that opens a TCP connection to an address outside, which must fail inside the start.

## Acceptance

- With the stub installed, `porelo` lists it and a `Detect` over a directory returns the stub's findings with their
  files and lines.
- A plugin that tries the network during `Detect` gets no connection, and one that cannot be started without
  network is not started at all.
- `AgentIsolationTest`'s new rule fails on a class outside `ecosystems/` that names the stub.

## To be checked

- That `unshare --user --net` works for `core` on the `ubuntu26.04` VM, on `fedora` under SELinux enforcing, and
  for `porelod` under its systemd user unit; where it does not, what the refusal says.
