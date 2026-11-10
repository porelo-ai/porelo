# [B571](README.md#b571) — A Task Container Finds Its Init

**Status:** Review. Filed 2026-11-09 under ST291, from the targeted run of the porelo-host stack on the shared test host.
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

## As built

- The Debian package requires `catatonit`, held by `PodmanFloorTest` alongside the Podman version floor.
- `TaskInit` reads rootless container configuration and checks the selected init and helper locations and the start's `$PATH` through the context's runner,
  without executing the init. The task launch refuses with exit 69 and names the `catatonit` package before
  remembering the project, registering hooks, building an image, or creating a container or task record.
  The CLI checks before taking an organization's project too. Dry runs do not probe the machine.
- Both Debian and Ubuntu package-check installs disable recommends, for Porelo and the agent package alike.
- `TaskInitTest` covers a fake path without the binary, an executable in that path and outside it, a directory,
  and a nonexecutable file, including an unusable legacy default shadowing an executable on `$PATH`.
  `TaskRunCommandTest.aMissingInitMakesNothing` holds the early refusal;
  the dependency and apt-install guards cover the package chain. Each new test was seen fail on behavior before
  the fix; the local handover report records commands, results, and sandbox refusals.

### Configured init

The preflight reads the system configuration, sorted system drop-ins, and the user's configuration and sorted
drop-ins. `CONTAINERS_CONF` replaces those files; `CONTAINERS_CONF_OVERRIDE` is read last. Later files replace
earlier values within each table, while `[containers] init_path` takes precedence over `[engine] init_path`.
Configured helper directories replace the default helper directories, retaining the legacy init-path precedence
and the final `$PATH` lookup. A missing configured init is refused by its path.

An unreadable file, uncertain syntax, or unavailable probe leaves the decision to Podman. The parser deliberately
accepts a conservative TOML subset: strings, ordinary scalar values, arrays, and bare table/key names. Constructs
it cannot interpret, including multiline strings, inline tables/array append attributes, and settings that alter
the engine's environment, stand aside rather than guess. `TaskInitConfigurationTest` covers configured valid and
missing inits, both tables, rootless file locations, ordering, environment overrides, helper replacement and PATH
fallback, and read/parse/probe uncertainty over fake filesystem roots.
