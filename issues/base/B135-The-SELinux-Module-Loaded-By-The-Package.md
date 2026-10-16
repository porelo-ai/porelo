# B135 — The SELinux Module Loaded By The Package

**Status:** Analyzed.
**Size:** M - maintainer scripts in both packages, the package check, docs, one run on an enforcing Fedora.

**What must be true.** Where SELinux is enabled, installing the `porelo` package loads Porelo's policy module, so a
task reaches the vault proxy with no manual step, and removing the package removes the module. `porelo doctor` reports
it present after a plain install.

## Why

Today the packages ship `selinux/porelo_socket.te` and `install-selinux-policy.sh` under `/usr/share/porelo/selinux`
and nothing more. Until a person runs `sudo /usr/share/porelo/selinux/install-selinux-policy.sh`, SELinux refuses a
task container's `connectto` on Porelo's sockets. The agent then reports an authentication failure, and `doctor` says
"MISSING - a task cannot reach the vault proxy". Asked on 2026-10-09: why does the install not do what is
known to be needed? Decided the same day: the package does it.

## The shape

- **`.rpm`, `%post`:** where `selinuxenabled` succeeds, compile and load the module, as `install-selinux-policy.sh`
  does today: `checkmodule`, `semodule_package`, `semodule -i`. It is compiled on the target on purpose, since a
  `.pp` is tied to the policy version of the machine that built it, and Porelo is built where there is no SELinux.
  Where SELinux is not enabled, nothing happens.
- **`.rpm`, `%postun` on removal** (not on upgrade): `semodule -r porelo_socket` where the module is loaded.
- **Requirements:** `checkpolicy` and `policycoreutils` in the `.rpm`.
- **`.deb`:** the same in `postinst` and `postrm`, where SELinux is enabled, which is rare on Debian and Ubuntu. The
  tools there are `Recommends`, not `Depends`, so a machine without SELinux does not install them.
- **A failure to load is not a failed install.** The package says what failed and names the script, and `doctor`
  reports it as today. Refusing the whole install over the module would leave a machine with nothing.
- **The script stays**, for a machine where SELinux is enabled after the install, and the docs say so.

## Plan

Read in the code on 2026-10-11: both packages carry `selinux/porelo_socket.te` and `install-selinux-policy.sh` under
`/usr/share/porelo/selinux` (`dist/dist-rpm/pom.xml`, `dist/dist-deb/pom.xml`) and no maintainer script at all;
`DoctorCommand` reports the module through `SocketContext.selinuxPresent()` and names the script.

1. **The script does the work, the packages call it.** `selinux/install-selinux-policy.sh` gains `--quiet-if-off`:
   where `selinuxenabled` fails it exits 0 and does nothing; a missing tool or a failed load says what failed and
   exits non-zero as today. A second script, `selinux/remove-selinux-policy.sh`, runs `semodule -r porelo_socket` only
   where `semodule -l` lists it. Both are installed beside the `.te`.
2. **`.rpm`:** in `dist/dist-rpm/pom.xml`, `postinstallScriptlet` runs the install script with `--quiet-if-off`
   and on a failure prints the script's own line to run by hand and exits 0; `postremoveScriptlet` runs the removal
   only when `$1` is 0, so an upgrade keeps the module and the next `%post` loads the new one. `requires` gains
   `checkpolicy` and `policycoreutils`; the description's sentence about installing by hand goes.
3. **`.deb`:** `postinst` (on `configure`) and `postrm` (on `remove` and `purge`) in
   `dist/dist-deb/src/deb/control/`, jdeb takes them from the control directory; `control` gains
   `Recommends: checkpolicy, policycoreutils, semodule-utils` (Debian's `semodule_package` is in `semodule-utils`).
4. **The package check reads them:** `dist/package-check` reads `rpm -qp --scripts` and the `.deb`'s `postinst` and
   `postrm` (`dpkg-deb -I`) and expects the install script in the install and `porelo_socket` in the removal, beside
   `Parity`; the install in the four containers stays green, SELinux being off there.
5. **Docs:** `doc/getting-started.md` and `doc/security.md` say the package loads the module where SELinux is enabled,
   and keep the script for a machine where it is enabled afterwards.
6. **Once on a machine:** a Fedora 43 leg (`acceptance/legs`, `--os fedora`), checked to be enforcing with
   `getenforce`, installs the built `.rpm` with `dnf`, starts a task that reaches the vault proxy, and `dnf remove`
   leaves no `porelo_socket` in `semodule -l`.

Red first: the package check's new reading of the scripts, against today's packages, which carry none.

## Acceptance

- On Fedora 43 with SELinux enforcing: `dnf install` of the package, then a task reaches the vault proxy with no
  manual step, and `doctor` reports `selinux policy  installed`. Seen to fail first with today's package: the same
  task fails to authenticate, and `doctor` reports it missing.
- `dnf remove` leaves no `porelo_socket` in `semodule -l`.
- On Ubuntu 26.04 and Debian 13 without SELinux: the install changes nothing of SELinux, and `doctor` reports it not
  needed.
- The package check (`dist/package-check`) reads `%post`/`%postun` and `postinst`/`postrm` and finds the module's
  load and removal.
