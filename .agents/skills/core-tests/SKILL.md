---
name: core-tests
description: porelo (core) test rules: the suites, installing on a machine to test, the rented test machines. Use when writing or running porelo tests.
---

# core-tests

## Installing on a machine to test

- **The legs module's `deploy` builds the packages CI publishes and installs them on a machine that stays**, with
  lingering and the daemon restarted; `--skip-build` installs what is in `target/`, and the interface is not
  installed by it.
- **`--account` installs into one account only** (`~/.local/bin`, `~/.local/share/porelo`, its own
  `porelod.service`) and stops unless the login PATH's binary, the daemon and the hooks are all that account's.
- **A non-login shell still runs the package's `porelo`**, since `~/.local/bin` is on PATH only after a login shell:
  use `bash -lc` or the full path.

## Tests

- **A unit test cannot reach the account it runs under.** The build sets `porelo.test.real.account.refused` for surefire, and `PoreloContext.real()`, `XdgPaths.current()` and a `~` then refuse with an `Error`; `PORELO_VAULT`, `SSH_AUTH_SOCK` and `DBUS_SESSION_BUS_ADDRESS` are kept from the test JVM. A refusal is the test's fault or the code's: give it the test's context, and take a home from `context.paths().xdg().home()`.
- **A test's result does not depend on the login or the umask of whoever runs it.** A test of code that reads the
  login runs under `TransportLogin` (`apps/app`), a login every transport takes, and one about a file's mode sets the
  umask itself with `Umask.under` (`wire`) and is `@Isolated`, since the umask is the process's.
- **Selecting features with `-Dcucumber.features` also requires
  `-Dcucumber.glue=org.fuin.porelo.acceptance`.** An undefined step (`■`) fails the run even when
  Maven reports no failures.
- **A test that needs podman belongs in the acceptance suite**; unit tests run without a container runtime, and inside
  a Porelo task too (no `podman`, installed `porelo` or `/run/user`, `no-new-privileges` set, and one uid in the user
  namespace).
- **A scenario about a packaged file cannot pass on a CI leg**, which builds on the machine and has no package
  installed; what a package holds is checked by `dist/package-check`.
- **A suite opens one ssh connection per run and a channel per scenario**; a connection per scenario is refused by CI.
- **JUnit is one set**: `org.junit:junit-bom` is imported before the fuin BOM, at the version Cucumber is built
  against.
- **A test that caches a vault passphrase forgets it in `finally`**: the tests' keys carry a per-JVM `porelo-test-<pid>`
  prefix, `KeyringLeakGuard` forgets and names any one a test leaves, and a full keyring stops every container.
- **A unit test leaves no clearance watcher running**: `OwnPorelo` answers `shield watch` for a class's helpers with a
  stand-in that makes its socket and exits, and `WatcherLeakGuard`, registered for every test in `apps/app`, fails a
  test whose watcher still runs ten seconds after it, names it and stops it.
- **`doc/reach.md` is held by `ReachDocumentTest`**, which reads its numbers off the code and refuses a row that does
  not say control or accident-catcher; a change that widens what a task can reach, hold, spend or produce changes
  that page in the same commit.
- **Review ranking is `ReviewRanking` in the gate, and git prints the patch in its order (`-O`)**; a dangerous file
  is never filed as generated or reformatting, and a rename is ranked by where it arrives.

## The rented test machines

- **Before renting a build leg, confirm that every tool snapshot the checkout requires is published.**
- **A CI leg boots a prepared snapshot through `porelo-machines`** (`./mvnw -s settings.xml -N exec:exec@machines
  -Dmachines.args='...'`: `leg`, `sweep`, `snapshot --os <os> --repo <porelo checkout>`), whose builder lives in
  `porelo-buildtools` beside the pins and compiles Porelo on the machine before taking the image.
- **The Ubuntu leg runs 26.04**, since 24.04 ships podman 4 and Porelo refuses it.
- **A snapshot restores only onto a disk at least its size**, so it is taken at 40 GB.
- **`podman images` as root shows nothing on a snapshot**: rootless podman keeps a store per user and the pre-pulled
  images belong to `build`, so ask as `build`.
- **Hetzner's Fedora image ships SELinux permissive**: enforcing needs the config, `/.autorelabel` and a reboot, and
  Porelo's policy module is loaded and checked with `semodule -l`.
- **A Fedora leg loads its own tree's policy module as root before it builds**, so the Build tests the module it
  carries rather than the one in the image.
- **`install -d -o build` owns only the last component**, so a root-owned parent stops rootless podman.
- **Provisioning is verified by compiling a probe, ordinarily and `--static --libc=musl`**, and changed against a
  live server, never by rebuilding the image.
- **A location is asked of the API per server type, within `eu-central`**, and a create retries only on
  `resource_limit_exceeded`; a run's servers are named after the run and the leg.

## Before a change is handed over

- **`mvn test` is unit tests only**: whatever needs podman or a machine is in the acceptance suite, which surefire
  never sees, so it runs against a real machine before a push is proposed:

      ./mvnw -q -s settings.xml -pl acceptance/legs compile exec:exec@legs -Dlegs.args="deploy --vm <user>@<host> --key <key> --account"
      ./mvnw -B -s settings.xml -pl acceptance/suite -am verify -Dsurefire.skip=true \
          -Dporelo.acceptance.host=<host> -Dporelo.acceptance.user=<user> 2>&1 | tee suite.log

- **`-am` builds the acceptance kit from this checkout**; without it the suite takes whatever kit was last installed,
  and a new step reads as undefined.
- **`-Dcucumber.filter.tags='not @slow'` is enough for a change to what the CLI prints**; the restart scenarios reboot
  the machine and run only where that is agreed.
- **The first terminal scenario of a run can fail where the same step then passes**, because the first pty races the
  login banner: run it again before believing it.
