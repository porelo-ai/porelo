# [B571](README.md#b571) — A Task Container Finds Its Init

**Status:** Backlog, since 2026-11-09. Filed 2026-11-09 under ST291, from the targeted run of the porelo-host stack on the shared test host.
**Size:** XS - one package dependency and its check, in `dist/dist-deb` and the start's preflight.

**What must be true.** A machine that installed porelo's package can start a task.
- porelo's `.deb` depends on `catatonit`, the init `podman create --init` looks up; every task container is started
  with `--init` (`ContainerSpec`).
- A start on a machine without it refuses before anything is made, naming the missing program and the package that
  brings it, instead of failing at `podman create` with `lookup init binary: exec: "catatonit": executable file not
  found in $PATH`.

## Why

On 2026-11-09 four scenarios of the targeted run (two of `gate-review`, two of `task-certified-by-the-person`) failed
on a test host installed without podman's recommended packages: every one where a container starts. podman only
recommends `catatonit`; porelo's package lists `podman`, `nftables`, `dnsmasq-base`, `git` and `util-linux`, but not
the init its own start needs. The local VMs had it only through recommends.

## Plan

- `dist/dist-deb/src/deb/control/control`: `catatonit` in `Depends`.
- The start's check of the programs it calls names `catatonit` beside podman's other needs, with the package that
  brings it. Red first: a unit test of that check over a fake path without `catatonit`.
- The package check of the VM chain installs with `--no-install-recommends`, so a missing dependency shows there.
