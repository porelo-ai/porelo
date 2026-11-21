---
name: core-build
description: porelo (core) build and publishing rules. Use when building, packaging or publishing porelo.
---

# core-build

## Building

    ./mvnw -s settings.xml -Pwhole clean install         # JDK 25+, the whole suite
    ./mvnw -s settings.xml -Pnative clean package        # native binaries
    ./mvnw -s settings.xml -Pnative,dist clean verify    # + .deb and .rpm

- **The tests tagged `slow` run only under `-Pwhole`**, which the whole suite before a hand-over and CI turn on; a run
  of the touched modules (`-pl <changed> -amd`) leaves them out, so **a change in `organization/` names `-Pwhole`**,
  as do the native and package builds above when their tests are to count. Only Organization Git Tree's three store
  classes carry the tag. `WholeSuiteProfileTest`, tagged `documents` so the shared rules check runs it on a change to
  `.github/` alone, fails when the profile no longer runs them, CI's build no longer names it, another class is
  tagged, or a module chooses tests by tag of its own.
- **Packaging binds to `verify`**, after native-image binds to `package`: `-Pnative,dist package` produces no package,
  or an older one beside a newer binary, which `./mvnw -pl dist/package-check compile exec:java@package-check` catches.
- **`agent.package.skip` is on by default and each agent turns it off**, since `dist` lives in `agents/pom.xml` and
  the aggregator and `porelo-agent-api` inherit it.
- **The root POM names its version as a literal `<porelo.version>`**, because `${project.version}` in a parent's
  `<dependencyManagement>` resolves against the inheriting module; an enforcer rule checks the two match.
- **An FFM downcall is not found by static analysis**: `./mvnw -s settings.xml -Pnative,ffm-check -Dagent=true
  -pl core,shield,vault -am verify` fails on one the tests made that is not registered, and
  `-Dporelo.ffm.update=true` records it; the changed `reachability-metadata.json` goes into the commit that adds or
  changes the downcall.
- **Two files are shell, since they run where there is no Java yet**:
  - `selinux/install-selinux-policy.sh` loads the shipped SELinux module, run once by an administrator;
  - `dist/dist-setup/porelo-setup.sh` prepares a machine that has nothing installed, run as root, and is also shipped
    in the `porelo` package at `/usr/share/porelo/setup/porelo-setup.sh`, where it keeps the channel the installed
    package came from.
- **The musl toolchain the hooks link against comes from the build tools**:
  `./mvnw -q -s settings.xml -N exec:exec@machines -Dmachines.args=musl`.
- **A new native image goes into its module's `porelo.cpu.images`**, or `porelo-cpu-check` never sees it.
- **FFM and static linking exclude each other** (`Linker.defaultLookup()` loads `libc.so.6`), so `porelo` and `porelod`
  link dynamically and the hooks, with no FFM, statically against musl; `NoForeignFunctionMemoryTest` holds them so.
- **A CI job compiles with the pinned GraalVM**, through `.github/actions/pinned-jdk`, never `setup-graalvm` with
  `'25'`.
- **`porelo-release` is a set of commands**, called through `exec-maven-plugin`; a pin beside the CLI is a
  `porelo.release.pin.<name>.*` property moved with `--pin <name>`, and `--min-age 3d` takes a release only once aged.
- **A change that needs a new `porelo-buildtools` is two pushes in order**: `porelo-buildtools` first, this one after
  its publish, at `porelo.buildtools.version`; the agent repositories resolve this one's artifacts after its publish.

## Publishing

- **porelo's Build ignores a push that changes only `**/*.md`, `mkdocs.yml`, `doc/**`, `.github/**` or
  `porelo.yml`** (`paths-ignore` in `build.yml`), even without `[skip ci]`. A change there that needs a Build is
  dispatched, or rides on the next code push.
- **Eight artifacts reach Central: `porelo-agent-api`, `porelo-wire`, `porelo-acceptance-kit`, `porelo-bom`, `porelo-build-api`, `porelo-ecosystem-api`, `porelo-ffm-check` and `porelo-cpu-check`**, the `wire` and `ecosystems/api` ones under Apache-2.0 and the others under GPL-3.0-or-later; the
  packages and the agents' go to Artifactory, `porelo-dist-deb` and `porelo-dist-rpm`.
- **Under `-Pcentral-sonatype-release` only `skipPublishing` counts**, default-deny in the root and off in those eight.
- **A snapshot publishes six of them, unsigned and without sources or javadoc jars**: on the `snapshots` channel
  Publish adds `-Dgpg.skip=true -Dmaven.source.skip=true -Dmaven.javadoc.skip=true -DskipInternalPublishing=true`,
  which `porelo-ffm-check` and `porelo-cpu-check` read as `skipPublishing`, since no repository resolves their snapshot
  and Central validates no snapshot. A release on a `v*` tag publishes all eight, signed, with both jars.
- **Artifactory is spoken to with curl, through `.github/scripts/artifactory.sh`**: no client is downloaded into a job
  holding the token. A Debian upload needs `deb.distribution`, `deb.component` and `deb.architecture` (Annotate
  permission), or it is stored and never indexed; build-info is not published.
- **`.github/workflows/artifactory-smoke.yml` checks the upload path without building**; run it after rotating the
  token.
- **The bill a package installs is its own** (`dist-deb`'s `makeBom`, its subject `porelo-dist-deb`).
- **The published binary is the one the Ubuntu leg tested**, fetched back with a manifest, since a hosted runner has
  no musl and a native image built on Ubuntu runs on Fedora too.
- **One commit runs green on the central forge and on a host's Forgejo, switched by forge variables, never by a branch
  in a workflow.** The legs, `proven` and the cancel of a failed commit's legs run only where `github.server_url` is
  GitHub's; `sweep.yml`, `artifactory-smoke.yml` and `delete-runs.yml` only where `PORELO_PUBLISH` is not `false`.
  Where it is `false`, `deploy` runs after a passed Build alone, for `main` and version tags, deploys the jars with the
  profile `PORELO_DEPLOY_PROFILE` names (refused when empty) to the registry `settings.xml` calls `porelo-forge`, and
  runs no package step; unset on the central forge, the profile stays `central-sonatype-release`.
  `PublishWorkflowTest` holds each condition.
- **Every agent repository carries the three-job build**: build and unit tests on `ubuntu-24.04`, publish with an
  index check, and acceptance on rented `ubuntu` and `fedora` from the package repository (`agents/README.md`).
