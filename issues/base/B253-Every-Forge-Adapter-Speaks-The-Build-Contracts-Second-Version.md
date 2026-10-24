# B253 — Every Forge Adapter Speaks the Build Contract's Second Version

**Status:** Backlog, for sprint 3.
**Size:** M - one contract change in `builds/api` (interface file, server, client and handshake), the stub and two
callers in `app-task` moved with it, its documentation and a changelog entry; the suite and the adapters are
`porelo-adapters`'s.

**What must be true.** Porelo asks a forge adapter only over `org.fuin.porelo.Build2`, whose `Build` method is
today's `Look` renamed, and refuses an adapter of `org.fuin.porelo.Build1` by name before it asks it anything; the
`porelo-build-api` on Central is all `porelo-build-contract` needs to run the one suite every adapter passes.

## Why

From porelo-project [PJ62](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj62): the forge
adapters live in one repository, `porelo-adapters`, beside one contract suite each passes, the build service's; the `github` adapter moves
there from `porelo-build-github`, and a `forgejo` adapter joins it. The contract stays Porelo's: `porelo-build-api`
and the build interface stay here, `look` is renamed `build` under a new protocol version, and every adapter is
released anew with it.

Not here: the suite is built in `porelo-adapters` as the module `porelo-build-contract`, and the `forgejo` adapter as
`porelo-adapter-forgejo` there, filed as porelo-build-github
[GH06](https://github.com/porelo-ai/porelo-build-github/blob/main/issues/README.md#gh06) and
[GH07](https://github.com/porelo-ai/porelo-build-github/blob/main/issues/README.md#gh07), in the repository that is
renamed `porelo-adapters`. The command a person asks an adapter through is [B256](README.md#b256).

## Plan

Read in `builds/api`, `builds/stub`, `apps/app-task`, `wire` and `core`'s `Builds`.

1. **The second version.** `org.fuin.porelo.Build1.varlink` (`builds/api/src/main/resources/org/fuin/porelo/build/api/`)
   becomes `org.fuin.porelo.Build2.varlink`, its `method Look` becoming `method Build` with the same answer and
   errors, and its `logs` parameter taking `none` beside `failure` and `all`. `BuildProtocol`: `INTERFACE` is `org.fuin.porelo.Build2`, `VERSION` 2, `LOOK` becomes
   `BUILD`; the interface's number and `VERSION` move together, and its Javadoc already counts a rename as a reason
   to raise it. `BuildServer` (`:48-55`) registers `Build`; `idl()` reads the file by `INTERFACE` and follows.
2. **The rename in Java.** `BuildReader.look` (`:40`) becomes `build`, and with it `InstalledBuildReader.look`
   (`:133-143`), `BuildWatch.Forge.look` and its callers (`BuildWatch.java:100`, `:226`, `:303-304`),
   `TaskWatchBuildsCommand` (`:140-141`), `StubReader.look` (`:108`), `FakeReader.look` (`:44`) and the tests that
   call them (`InstalledBuildReaderTest`, `StubReaderTest`, `BuildWatchTest`). The class names stay: a forge adapter
   is what the code calls a build reader.
3. **A `Build1` adapter refused by name.** Today the version is checked in `InstalledBuildReader.start`
   (`:196-208`), which refuses an unknown `protocolVersion` naming the executable and both versions;
   `TaskWatchBuildsCommand` (`:175-181`) writes that message to its log and the task's `buildProblem`. An adapter of
   `Build1` never reaches that check once Porelo asks `Build2.Describe`: `VarlinkServer.handle` (`wire`, `:252-254`)
   answers a method it does not have with `org.varlink.service.MethodNotFound`, which `start` wraps as "failed to
   start". So when `Describe` is answered `MethodNotFound`, `start` asks `org.varlink.service.GetInfo`, which every
   `VarlinkServer` answers with its `interfaces` (`:116-126`), and refuses naming the executable, the build
   interface it lists and `org.fuin.porelo.Build2`, in the sentence the version refusal has today. Any other error
   stays "failed to start".
4. **The interface file held to the server.** No test holds `BuildServer`'s methods against its interface file
   (`InterfaceDescriptionTest` holds the daemon's interface only): a package-private accessor to the server's
   `VarlinkServer.methodNames()` and a test that they are the file's methods, so a contract another repository builds
   against cannot keep a method its file renamed.
5. **`none` among the logs.** `Build.Logs` (`Build.java:68-101`) gains `NONE` beside `FAILURE` and `ALL`, and
   `BuildServer.logs` (`:127-137`) takes `none` where it refuses anything else as `InvalidParameter`; an adapter asked
   for `none` answers the verdict and the jobs and downloads no log. [B256](README.md#b256)'s `--logs=none` asks for
   it. A project file's `builds.logs` stays `failure` or `all` (`Builds.java:50-53`): a task that follows its pushes
   always wants a failed job's log.
6. **The stub** moves with steps 1, 2 and 5: it serves through `BuildMain`, so it speaks `Build2` once the API
   does, returns no log for `none`, and `task-builds.feature` reads builds through it as before.
7. **What `porelo-build-api` must publish for `porelo-build-contract`: nothing new.** It already opts into Central
   (`builds/api/pom.xml`, `skipPublishing` false) and the BOM manages it. A suite in another repository drives an
   adapter as Porelo does, through `InstalledBuildReader` over its socket, with an executable that starts the
   adapter's main as `InstalledBuildReaderTest.java()` (`:189-198`) does; what it compares is public - the records
   (`Build`, `Build.Job`, `Build.Logs`, `Target`), the refusals (`BuildRefused`, `BuildRefused.Reason`), the bounds
   (`Build.TAIL`, `Build.MOST_JOBS`, `Build.MOST_LOG_BYTES`) and `BuildReader`, `BuildMain`, `InstalledBuildReader`.
   Package-private is only the wire mapping (`Build.of`, `Build.asMap`, `BuildRefused.Reason.of`,
   `BuildProtocol.error`), which a suite going through the socket never calls. `FakeReader` is test code, not in
   the jar, and not needed there: it stands in for an adapter, where the suite needs a forge's recorded answers,
   which are `porelo-adapters`'s.
8. **The documentation**: `builds/api/README.md`, `doc/build-readers.md` (`:19-21`, `:28`) and `doc/decisions.md`
   (`:21`, `:230-232`) name `Build2` and `build`; `doc/running.md` (`:289`) names the package `porelo-adapter-github`
   once that package is published, not before. A changelog entry under `changelog/unreleased/`: an installed adapter
   of the first version is refused, naming it. `AGENTS.md` (`:25`, `:374`) names four artifacts reaching Central, but
   `porelo-build-api` 0.5.0 is there too (asked of Central): it is named among them.
9. **The release, decided:** porelo with `Build2` and the first `porelo-adapter-github` go out in the same gathered
   push, so the time in which an installed `Build1` adapter is refused lasts hours at most. porelo's packages name
   no forge's package: no `Breaks:` and no `Conflicts:` on `porelo-build-github`.

**Red first:** `InstalledBuildReaderTest.refusesAnAdapterOfTheFirstVersionByName` - an executable serving
`org.fuin.porelo.Build1` with a `Describe` answering version 1 - expects a refusal naming `org.fuin.porelo.Build1`
and `org.fuin.porelo.Build2`; today that adapter is accepted.

## Acceptance

- An adapter of `Build1` is refused before it is asked anything, the message naming its executable,
  `org.fuin.porelo.Build1` and `org.fuin.porelo.Build2`; the test is seen to fail first, where it was accepted.
- An adapter answering `Build2.Describe` with another version is refused as today
  (`refusesAReaderThatSpeaksAnotherVersionOfTheProtocol`), and one that fails otherwise still reads as "failed to
  start".
- Asked for `none`, the stub answers the verdict and the jobs with no log, and `BuildServer` no longer refuses
  `none` as `InvalidParameter`; a project file's `builds.logs: none` is still refused.
- `BuildServer`'s methods are its interface file's, held by a test seen to fail with a method registered that the
  file lacks.
- `Build1`, `Look` and a `look` method appear in `builds/`, `apps/` and `doc/` only in the test serving the first
  version.
- The acceptance suite's `task-builds.feature` passes on a VM with the stub on `Build2`.

## To be checked

- That an adapter built on a released `porelo-build-api` of `Build1` - `porelo-build-github`'s - answers
  `MethodNotFound` to `org.fuin.porelo.Build2.Describe` and lists `org.fuin.porelo.Build1` in `GetInfo`: read in
  this checkout's `VarlinkServer` only; the released adapter and the `porelo-wire` it was built on are another
  repository's.
- That a project file can name a forge on the host's own network: `builds.api` takes any `https://` address and the
  adapter runs on the host, never in a task; whether it trusts a certificate authority the host names, as a local
  Forgejo's may need, is not read.
