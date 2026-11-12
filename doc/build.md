# Structure

It is built from a Maven multi-module Java 25 project and ships as signed native binaries, with no language runtime
on the host:

- **`porelo`** — the CLI, and the only command an operator types: it starts and stops task containers, edits
  configuration, and unlocks the credential vault.
- **`porelod`** — an optional daemon that serves the same domain over a unix socket for the desktop client; nothing in
  the CLI path needs it running.
- **supervisor hook** — starts the per-container supervisor (vault broker, SSH signer, git gate, clearance hub) as the
  container is created, and reaps it once the container is gone.
- **nft hook** — loads the pre-generated nftables ruleset inside the container's network namespace before the workload
  gets to run; if it fails, the container does not start, so there is no window without egress control.
- **NFLOG reader hook** — starts the reader that turns the kernel's packet-drop records into Porelo's network audit
  trail.

The three hooks are statically linked against musl and make no native calls, so they run under whatever the OCI runtime
hands them at container-create time.

## Building

```
./mvnw -Pwhole clean install
```

`-Pwhole` runs the tests tagged `slow` as well, which a build without it leaves out: the whole suite before a
hand-over and CI turn it on, and a run of only the touched modules (`-pl <changed> -amd`) leaves it off. A change in
`organization/` names it, and so do the native and package builds below when their tests are to count.

Requires JDK 25 or later. Byte Buddy is pinned to 1.17.8 via `org.fuin:bom`,
because earlier versions reject class-file major version 69.

The FFM and CPU checks live in [build/](https://github.com/porelo-ai/porelo/blob/main/build/README.md),
built and published with Porelo at `porelo.version`. Each profile that enables a check declares it as an
exec plugin dependency, so the reactor builds it before the module it checks.

`porelo-machines`, `porelo-release` and the package check's `porelo-json` stay in
[porelo-buildtools](https://github.com/porelo-ai/porelo-buildtools), taken from Maven Central at the root
pom's `porelo.buildtools.version`; the package check itself lives in `dist/package-check`. The build tools'
snapshots come from the Central snapshot repository that `settings.xml` names, so pass `-s settings.xml`
as CI does unless your own settings name it too.

### Native binaries

```
export JAVA_HOME=/path/to/graalvm-25
./mvnw -Pnative clean package
```

Produces `apps/app/target/porelo`, `daemon/target/porelod` and the three hook binaries
in `hooks/target/`.

The hooks are linked statically against musl, which needs a cross-toolchain:

```
./mvnw -q -s settings.xml -N exec:exec@machines -Dmachines.args=musl
```

It runs the installer the build tools carry, the same one a test machine's snapshot runs, with both downloads
checked against their digests. It installs to `x86_64-linux-musl-native` in the account's local `opt` folder (`MUSL_PREFIX` in the
environment moves it; tell the build with `-Dmusl.home=...`). Nothing needs to be added to `PATH`.

`porelo` and `porelod` are dynamically linked, and deliberately so: they use the
Foreign Function & Memory API, and `Linker.defaultLookup()` dlopens `libc.so.6`,
which a static image cannot do.

### CPU

Every image is built for x86-64 v1, the baseline every x86-64 CPU has: `-march=x86-64` in the root
POM's native build arguments. native-image's default is the build host's generation - v3 on any
recent machine - and such a binary refuses to start on an older CPU or on a virtual machine that
hides AVX2, which an operator learns only by running it.

Each image carries the list of CPU features it checks for at startup. `porelo-cpu-check` reads
that list in `package`, right after the images are built, and fails unless it is exactly
`[CX8, CMOV, FXSR, MMX, SSE, SSE2]` - in either direction, since a list that changed means the
target changed. A native profile turns it on and names its images in `porelo.cpu.images`; an image not named there
is not checked.

### FFM metadata

Panama downcalls are not discovered by native-image's static analysis. An unregistered one is not a
build error, it is a `MissingForeignRegistrationError` at runtime in the shipped binary, so the
registrations are generated from a test run rather than maintained by hand:

```
./mvnw -s settings.xml -Pnative,ffm-check -Dagent=true -pl core,shield,vault -am verify                            # verify; fails on drift
./mvnw -s settings.xml -Pnative,ffm-check -Dagent=true -pl core,shield,vault -am verify -Dporelo.ffm.update=true  # writes the registrations
```

The check is `porelo-ffm-check`, the exec plugin's dependency, and CI runs it. With `-Dporelo.ffm.update=true` it
writes what the run found into `reachability-metadata.json`, which is kept beside the code that makes the downcall.

### Packages

```
export JAVA_HOME=/path/to/graalvm-25
./mvnw -Pnative,dist clean verify
```

Produces a `.deb` and an `.rpm` for Porelo itself, in `dist/dist-deb/target` and
`dist/dist-rpm/target`, plus one of each per agent under `agents/*/target`.

**`verify`, not `package`.** Both packagers are bound to `verify`, because
native-image binds to `package` and a plugin inherited from a parent runs before
the module's own — so at `package` time the binary does not exist yet. Running
`-Pnative,dist package` therefore rebuilds every binary and produces no packages
at all, or worse, leaves an older package in place beside a newer binary.

The `porelo` package installs:

| Path | Contents |
|---|---|
| `/usr/bin/porelo`, `/usr/bin/porelod` | the two dynamically linked binaries |
| `/usr/libexec/porelo/hooks/` | the three static hook binaries |

It registers nothing. Podman reads OCI hook descriptors per user, so each
operator runs `porelo setup` once; that writes the descriptors into
`$XDG_CONFIG_HOME/containers` and points podman at them. Nothing in the package touches
a system-wide podman configuration.

Agents are not part of it. They ship as separate `porelo-agent-*` packages that
depend on `porelo` and install into `/usr/libexec/porelo/agents`, which `porelo`
scans at runtime; an agent of another vendor is found instead by one description file in
`/usr/share/porelo/agents.d/` - see [the agent guide](https://github.com/porelo-ai/porelo/blob/main/agents/README.md).

A snapshot build produces `0.1.0~SNAPSHOT`, with a tilde, because both dpkg and
rpm sort `~` below everything; left as `-SNAPSHOT` it would sort *above* the
release and neither apt nor dnf would upgrade from it.

To check the built packages, after `./mvnw install` and a `-Pdist verify`:

```
./mvnw -s settings.xml -N exec:java@package-check
```

It runs `porelo-package-check` from the root alone. It compares the deb and the rpm against each other — they declare their contents
in two different plugin syntaxes in two different modules, so they can drift —
and then installs both, plus an agent package, in clean Ubuntu and Fedora
containers to confirm the dependencies resolve and `porelo setup` finds the
packaged hooks. It also fails if a package is older than the binary it carries,
which is the `package`-instead-of-`verify` mistake above.

### Installing on a machine that stays

```
./mvnw -q -s settings.xml -pl acceptance/legs compile exec:exec@legs -Dlegs.args="deploy --vm user@host --key <key>"
./mvnw -q -s settings.xml -pl acceptance/legs compile exec:exec@legs -Dlegs.args="deploy --vm user@host --key <key> --skip-build"
```

Builds the packages CI would publish and installs them on that machine. The command lives in this
tree's `acceptance/legs`, which knows where its modules leave the packages; `--vm` and `--key` default
to `PORELO_VM` and `PORELO_VM_KEY`. `--account` installs into one account only. Any other `porelo-machines` command runs as
`./mvnw -s settings.xml -N exec:exec@machines -Dmachines.args='<command> <options>'`, in a JVM of its
own, and that is how the workflows here run a leg, a sweep and the pinned GraalVM.

**A leg** rents a machine booted from a snapshot, builds the product there and runs the acceptance
suite. Its features run under four accounts beside each other (`--accounts 4`), and what touches the
whole machine (`@restart`) runs alone afterwards. Every image comes through a pull-through cache on
the machine, so no account waits on Docker Hub. Before ordinary features, the leg runs
[the host-services listener script](https://github.com/porelo-ai/porelo/blob/main/acceptance/suite/src/test/host-services/listener.sh)
with `up` through its root SSH connection. It prepares a TLS listener on exactly `127.0.0.1:443` for
`forge.host-service.test`, answering `GET /` with `host-service-ok`. A fresh CA's public certificate is readable
at `/var/tmp/porelo-host-services/ca.pem`; its signing key is deleted before the listener starts, and private
material stays root-only under `/root/porelo-host-services`. Preparation refuses an occupied port or leftovers.
Only a successfully prepared suite runner gets `PORELO_ACCEPTANCE_HOST_SERVICES`. The leg calls `down` afterwards,
also when the suite fails; a failed stop is reported and retains the files for cleanup. Restart scenarios run last,
with one account too, so they cannot remove the transient listener before the host-services feature uses it.
A direct invocation of the suite needs the same root preparation and cleanup on its VM before setting that variable.
After its scenarios a leg prints what the cache
served. On the run of 2026-10-02 both legs printed 20 manifests and 20 blobs, `ubuntu` and `alpine`.
A leg takes 15-20 minutes, against 26 when the scenarios ran one after another.

**The legs start beside the unit tests**, not after them, and a failed unit test cancels the run: each
leg's clean-up still runs and deletes the machine it rented. Measured on 2026-10-02 with a deliberately red
test: the unit test failed after a minute, both legs were cancelled 30 seconds later, each deleted its one
server, and nothing was published. `Publish` builds the jars once, in the step that publishes them, and runs
no unit test again.

### Testing a package upgrade in one account

**Not run here.** The package upgrade feature is one real-machine scenario, run alone in a disposable account.
Allow roughly ten minutes on a warm VM; that is an estimate until its first run. It never reboots the machine or
installs machine-wide packages. It replaces that account's CLI, daemon and hooks, stops and starts its own transient
daemon, and removes only its recorded tasks. It uses the kit's one SSH connection, a test vault, a local TLS fake
provider, a queue transport that always defers delivery, and the installed MCP helper. Nothing calls a paid provider.

1. Prepare a dedicated, unprivileged account on a Debian or Ubuntu VM using the normal
   [machine preparation](running.md#preparing-a-machine) and `deploy --account` path above. This step is **not run here**.
   An administrator must already have installed podman 5 or later, nftables, dnsmasq with nftset support, rootless
   uid/gid mappings, the kernel-keyring library, systemd user services, `python3` (the scenario's driver runs on
   the VM), OpenSSL and `dpkg-deb`, and enabled lingering.
   The account's login PATH must find the `porelo` the deploy places in its own bin directory.
   The scenario refuses existing containers,
   registered projects, custom TLS trust, nonstandard XDG roots, or `~/target/porelo-upgrade` leftovers. Keep the account
   exclusive for the run. Neither package installation in the scenario requires root; missing machine prerequisites
   must be prepared by the administrator, never by an invented privilege inside the scenario.

2. Stage the input files on that VM and set the following environment variables on the machine running Maven.
   This step is **not run here**. Paths must be absolute VM paths to regular files, with no symlink. Obtain the
   baseline from a published package before the new naming behavior and take its digest from the publication's
   recorded metadata. Pick a recent predecessor that already supports the stub's sessions, routes and task MCP.
   The baseline must itself emit `porelo-porelo-kept`; a candidate container renamed to it is refused by the setup.
   The candidate is the `.deb` built from the checkout under test, the same payload `deploy --account` extracts.
   The two versions and digests are checked before the first install and printed in the scenario report.

   | Variable | Value |
   |---|---|
   | `PORELO_ACCEPTANCE_HOST` | VM address reachable by SSH; `127.0.0.1` when Maven runs on the VM itself |
   | `PORELO_ACCEPTANCE_UPGRADE_USER` | Dedicated VM login, also passed as the suite's user |
   | `PORELO_ACCEPTANCE_KEY` | SSH authentication material, supplied as for other acceptance runs |
   | `PORELO_ACCEPTANCE_BASELINE` | Absolute VM path to the published predecessor `.deb` |
   | `PORELO_ACCEPTANCE_BASELINE_VERSION` | Its exact `dpkg-deb -f FILE Version` value |
   | `PORELO_ACCEPTANCE_BASELINE_SHA256` | Its recorded SHA-256 |
   | `PORELO_ACCEPTANCE_CANDIDATE` | Absolute VM path to this build's `.deb` |
   | `PORELO_ACCEPTANCE_CANDIDATE_VERSION` | Its exact package version |
   | `PORELO_ACCEPTANCE_CANDIDATE_SHA256` | Its SHA-256 |
   | `PORELO_ACCEPTANCE_STUB`, `PORELO_ACCEPTANCE_STUB_SHA256` | VM path and digest of `porelo-agent-stub`, compatible with both packages |
   | `PORELO_ACCEPTANCE_MCP`, `PORELO_ACCEPTANCE_MCP_SHA256` | VM path and digest of the `porelo-mcp` native executable |
   | `PORELO_ACCEPTANCE_FILTER`, `PORELO_ACCEPTANCE_FILTER_SHA256` | VM path and digest of `porelo-message-sluice-filter` |

   The helper files are installed explicitly into the account. The provider and transport fixtures are written by
   the scenario, with a fresh TLS certificate trusted only by this account. The suite supplies
   `PORELO_ACCEPTANCE_FAKE_CREDENTIAL=sk-porelo-acceptance-not-a-real-key`; no real credential is needed.

3. Run this command from the candidate checkout, with JDK 25 and the variables above. It is **not run here**.

   ```sh
   ./mvnw -B -s settings.xml -o -pl acceptance/suite -am verify -Dsurefire.skip=true \
     -Dit.test=AcceptanceIT -Dfailsafe.failIfNoSpecifiedTests=false \
     -Dporelo.acceptance.host="$PORELO_ACCEPTANCE_HOST" \
     -Dporelo.acceptance.user="$PORELO_ACCEPTANCE_UPGRADE_USER" \
     -Dcucumber.filter.tags=@upgrade \
     -Dcucumber.glue=org.fuin.porelo.acceptance \
     -Dcucumber.features=classpath:org/fuin/porelo/acceptance/task/task-package-upgrade.feature
   ```

   Expected: one passing scenario, no undefined steps. The old task's id, name, initial start time, helpers, gate,
   attach, mailbox and route survive the package replacement and the separate daemon restart. A fresh clearance
   event reaches the daemon's restored subscription; MCP resumes only the old parent's child. Stop and resume by
   logical name retains the sentinel, original session input, signed queued mail and route token. New containers
   are `porelo-fresh` and `porelo-upgrade_fresh`. Those last assertions require the candidate's new-name allocator.
   Selecting `AcceptanceIT` keeps the kit's separate `MachineIT` connection probe out of this run; all scenario
   channels share the one SSH connection.

4. Inspect the scenario report and the account after the run. This step is **not run here**. Successful cleanup
   removes its task containers, fixture service, TLS trust and staging directory; the kit removes its projects and
   test vault and restores the account's daemon if it had been running. The account remains on the candidate. A
   failed cleanup fails the run and names the survivor; retain the report and inspect that account before retrying.
   This feature has `@upgrade @isolated`, without `@slow` or `@restart`. The suite's default selection and ordinary CI
   legs explicitly exclude `@upgrade`; the command above selects it. A custom tag expression for an ordinary run
   should also exclude `@upgrade`. Once selected, missing inputs fail instead of skipping.

## The documentation site

The site at `https://porelo-ai.github.io` is built in the repository `porelo-ai/porelo-ai.github.io`, from this
repository's `doc/` in the order `mkdocs.yml` gives and from the other repositories' beside it. `mkdocs build
--strict` here checks this repository's pages and their links; that repository's `build/assemble.py --local
porelo=<this checkout>` shows the whole site with a change before it is pushed.
