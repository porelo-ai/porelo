# B249 — The Hooks Run dnsmasq and podman by a Fixed Path

```json
{
  "$schema": "issue-1.json",
  "id": "B249",
  "title": "The Hooks Run dnsmasq and podman by a Fixed Path",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 76,
  "blockedBy": [],
  "covers": "The resolver hook, the shield's resolver and the doctor's probe take `dnsmasq` by a fixed root-owned path, as [B239](README.md#b239) does `nsenter` and `nft`; `podman` stays called by name",
  "openQuestions": 0
}
```

**Why this size.** S: one word made a resolved path in three places (`hooks`, `shield`, the doctor in `apps/app`),
each with a small test, and no contract another repository reads. Estimate: 45 minutes (Core's median for S).
`podman` stays called by name, so it stays an S.

**What must be true.** Every program a hook starts inside or for a container's namespace is taken by a fixed,
root-owned path, as [B239](README.md#b239) takes `nsenter` and `nft`. None is found through the caller's `PATH`.
Changing `PATH` does not change which `dnsmasq` resolves a task's names.

## Why

[B239](README.md#b239) resolves `nsenter` and `nft` with `SystemProgram.find`. Two programs on the same path are
still bare names:

- **`dnsmasq`:** the supervisor hook hands `nsenter` the bare word `dnsmasq`, and `nsenter` looks it up in the
  `PATH` it was given. A `dnsmasq` placed earlier there (for example in `$XDG_BIN_HOME`) runs in the task's network
  namespace instead of the system's, and the task's DNS and nftset enforcement is then whatever it does.
  `porelo shield dns` builds the same command through `DnsPolicy.command`. `porelo doctor` asks the bare
  `dnsmasq --version` (`DnsmasqProbe.versionCommand`) whether nftset is supported, so it may judge another
  `dnsmasq` than the one the hook runs.
- **`podman`:** `EgressPolicy.inNamespace` starts `podman unshare` by name. This is not a hook: it runs in
  `porelo shield watch` and `porelo shield dns`, as the person. Porelo starts every task with a bare `podman` too
  (`PoreloContext.podman`, `TaskRunner`), so there is no resolved `podman` path to reuse today.

Both start with the caller's own rights, not with more, so this is not the escalation [B239](README.md#b239)
closed. But each one decides what a task may reach.

## Plan

Read in today's code. `SupervisorHook.startResolver` (`hooks`) builds `nsenter --target <pid> --net dnsmasq ...`
with `SystemProgram.find("nsenter")` and the bare `dnsmasq`. `DnsPolicy.command` (`shield`) takes a
`Function<String, Path>` for `nsenter` already, but passes `dnsmasq` bare. `EgressPolicy.nsenter` shows the way:
it resolves `command[0]` through the same function. The hook entry `HookInstaller` writes carries no `env`, so
the `PATH` `nsenter` searches is whatever the runtime hands the hook.

1. **The hook.** `SupervisorHook` gains a package-private `static List<String> resolverCommand(long pid, Path
   config, Function<String, Path> programs)`, as `NftHook.command` has one. It resolves both `nsenter` and
   `dnsmasq` through `programs`. `startResolver` calls it with `SystemProgram::find`. A `dnsmasq` that is missing
   or not root-owned fails the hook as an unsafe `nsenter` does today. The hook is soft-fail, so the container
   starts without a resolver and the hook's log says why.
2. **The shield's resolver.** `DnsPolicy.command` resolves `dnsmasq` through its `programs` function, as
   `EgressPolicy.nsenter` resolves `nft`.
3. **The doctor.** `DnsmasqProbe.versionCommand` returns the resolved path (`SystemProgram.find("dnsmasq")`).
   When none is found, the doctor's `dnsmasq nftset` line says so instead of running a `dnsmasq` from `PATH`.
4. **`podman`.** It stays called by name. It is not a hook, it runs with the person's rights, and Porelo trusts the
   same `podman` for the whole task.
5. **A dangling link.** `SystemProgram.find` refuses when a fixed directory holds a dangling link of the name
   (`toRealPath` throws). The directories are root-owned, so only root can place one. It stays fail-closed; the
   method's comment says so.
6. **Docs.** `doc/security.md`, where it says which programs the hooks run, names `dnsmasq` among those taken by a
   fixed path, and says that `podman` comes from `PATH`.

## Red-first test

- `DnsPolicyTest.runsInsideTheContainersNamespace` (`shield`) expects `/usr/sbin/dnsmasq` from its stand-in
  lookup. Today it gets the bare `dnsmasq`, so it fails.
- A new `SupervisorHookTest` in `hooks` (beside `NftHookTest`) asks `resolverCommand` with a stand-in lookup and
  expects both programs resolved. A second case gives a lookup that refuses `dnsmasq` and expects the refusal to
  reach the hook's log, as `NftHookTest` does for `nsenter`.
- `DnsmasqProbeTest` expects an absolute path in `versionCommand`; today it asserts the bare name.

## Risks

- A system whose only `dnsmasq` is outside `/usr/sbin`, `/usr/bin`, `/sbin` and `/bin` (for example under
  `/usr/local/sbin`) loses its resolver. Today that works. The doctor then says so, and the docs name the fixed
  directories.
- Under rootless podman an unmapped owner reads as the overflow uid, and `SystemProgram` accepts that only in
  the fixed distribution directories. `dnsmasq` from a distribution package is there, so this holds. To be seen
  on the test VM.

## Blockers

None.

## Acceptance

- With a `dnsmasq` stand-in placed first in `PATH`, a task's resolver is still the system's `dnsmasq` (its pid's
  `/proc/<pid>/exe`). Seen to fail first: today the stand-in runs.
- `porelo doctor` judges the `dnsmasq` the hook runs, not one from `PATH`.
- The red-first tests above pass.

## Decided 2026-11-13 (operator)

`podman` stays called by name; only `dnsmasq` goes by a fixed path. The docs say, with this build, that `podman`
comes from `PATH`. Size S.

## To be checked

- On the test VM: that the hook finds `/usr/sbin/dnsmasq` under rootless podman's user namespace, and that a
  stand-in first in `PATH` is not run.
