# B249 — The Hooks Run dnsmasq and podman by a Fixed Path

**Status:** Idea.

**What must be true.** Every program a hook starts inside or for a container's namespace is taken by a fixed,
root-owned path, as [B239](README.md#b239) takes `nsenter` and `nft`. None is found
through the caller's `PATH`.

## Why

[B239](README.md#b239) resolves `nsenter` and `nft` with `SystemProgram.find`. Two programs on the same path are still bare names:

- **`dnsmasq`:** `SupervisorHook.run` and `DnsPolicy.command` hand `nsenter` the bare word `dnsmasq`, and `nsenter`
  looks it up in `PATH`. A `dnsmasq` placed earlier in `PATH` (for example `~/.local/bin`) runs in the task's
  network namespace instead of the system's, and the task's DNS and nftset enforcement is then whatever it does.
- **`podman`:** `EgressPolicy.inNamespace` starts `podman unshare` by name.

Both start with the caller's own rights, not with more, so this is not the escalation [B239](README.md#b239) closed. But each one
decides what a task may reach.

## The shape proposed

- `dnsmasq` through `programs.apply`, as `nft` goes, in both places. `SystemProgram.find` already refuses one that is
  not root-owned in a root-owned directory.
- `podman` by the path Porelo already resolved for the task's own start, if there is one; to be read.

## To be checked

- Which path the hooks' `podman` comes from today, and whether the start already holds a resolved one.
- `DnsPolicyTest.runsInsideTheContainersNamespace` asserts the bare `dnsmasq` today and changes with the fix.
- `SystemProgram.find` refuses when an earlier directory holds a dangling link of that name (`toRealPath` throws
  outside the absent-file case) instead of trying the next directory. It fails closed; whether to go on is to be
  decided with this.
