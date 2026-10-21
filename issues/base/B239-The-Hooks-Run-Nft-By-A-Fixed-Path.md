# B239 — The Hooks Run Nft By A Fixed Path

**Status:** Idea.

**What must be true.** The hooks run `nsenter` and `nft` from the distribution's own directories, found by a fixed
list and refused when anybody but root could have placed them, never through the `PATH` of whoever started podman.

## Why

Measured on the VM: a per-account install started by a runner with a `PATH` lacking `/usr/sbin` failed every task
start with `nsenter: failed to execute nft: No such file or directory` in `hooks.log` (`NftHook`, `SupervisorHook`,
`ReaderHook` and `EgressPolicy` all name the bare programs). That fails closed, but it makes the firewall depend on an
inherited `PATH`: a directory earlier in it holding another `nft` would load whatever that program loads. The hooks run
with the rights of the person running podman, not more, so this is no escalation; it is a firewall that is only as
fixed as somebody's `PATH`.
