# B239 — The Hooks Run Nft By A Fixed Path

**Status:** Implementing.
**Size:** M - a shared lookup in wire, four callers in hooks and shield, and their unit tests; no dependency changes.

**What must be true.** The hooks run `nsenter` and `nft` from the distribution's own directories, found by a fixed
list and refused when anybody but root could have placed them, never through the `PATH` of whoever started podman.

## Why

Measured on the VM: a per-account install started by a runner with a `PATH` lacking `/usr/sbin` failed every task
start with `nsenter: failed to execute nft: No such file or directory` in `hooks.log` (`NftHook`, `SupervisorHook`,
`ReaderHook` and `EgressPolicy` all name the bare programs). That fails closed, but it makes the firewall depend on an
inherited `PATH`: a directory earlier in it holding another `nft` would load whatever that program loads. The hooks run
with the rights of the person running podman, not more, so this is no escalation; it is a firewall that is only as
fixed as somebody's `PATH`.

## Plan

Read in `NftHook`, `SupervisorHook`, `ReaderHook`, `EgressPolicy`, their tests, `DnsPolicy.command` and the hooks, shield and wire POMs.

1. Add `SystemProgram` to `wire`, the one module both the hooks and shield depend on, guarded against FFM; a new
   public class of a published artifact, which only grows it.
   Search only `/usr/sbin`, `/usr/bin`, `/sbin`, `/bin`, in that order. Resolve distribution
   symlinks and check the file and directory with NIO Unix attributes: uid zero, no group or other write,
   regular executable file. Refuse an unsafe candidate rather than falling through; name the path and reason.
2. Use the checked absolute paths for every `nsenter` and `nft` execution in the three hooks and
   `EgressPolicy`. Keep ruleset input on stdin and the nft hook's existing fail-closed boundary.
   The shared namespace builder also fixes `DnsPolicy.command`; give command construction a test seam so tests need neither installed programs nor privileges.
3. **Red first:** `SystemProgramTest` uses temporary directories and supplied ownership attributes to
   reproduce an untrusted file, writable directory, missing program and successful lookup. Command tests
   first fail on today's bare program names in `NftHook` and `EgressPolicy`, then hold absolute arguments.
4. The native hooks built and the acceptance suite run on the VM on the exact commit, since every task's start
   runs the hooks.

## Acceptance

- An untrusted file or group-writable directory is refused with its path and reason. Seen to fail first:
  the lookup accepts each unsafe candidate when its respective check is absent.
- A missing program is refused, naming the searched paths, and a safe executable yields its absolute path.
  Seen to fail first: returning an unchecked candidate or a bare name violates these assertions.
- The nft hook and live egress commands contain absolute paths for both programs. Seen to fail first:
  the current command lists contain bare `nsenter` and `nft`.
- An unsafe lookup on the nft start path refuses the container and logs the reason. Seen to fail first:
  accepting the unsafe candidate lets the hook proceed.
- Hooks and wire remain free of FFM; their existing architecture tests pass.


**Decided while building:**

- Check ancestor directories and resolved symlink targets as well as the named distribution directory;
  return the resolved absolute path. An unsafe candidate stops lookup rather than falling through.
- Ownership tests supply Unix uid attributes while using real temporary files and permission bits,
  so the unit suite needs no root privileges.
- **Root is the host's root as the caller's user namespace sees it.** Measured on the VM: a hook runs in rootless
  podman's namespace, where the caller is uid 0 and the host's root is unmapped (`uid_map` `0 1008 1` /
  `1 624288 65536`), so `/usr/bin` reads as the kernel's overflow uid 65534. The owner must be the uid that host uid
  0 maps to, or the overflow uid where it is unmapped; the caller's own uid 0 is refused.
- **The exposure left:** in a rootless namespace every unmapped host account reads as the overflow uid too, so a
  program in the fixed directories owned by another non-root account passes. That needs a non-root owner on a
  system directory, which only root can make.
- **procfs is read to its end through a stream.** Measured on the VM: in the native hook, `Files.readString` of
  `/proc/sys/kernel/overflowuid`, which reports a size of 0, gave `6` of `65534`. Only the native image shows it, so
  the acceptance suite's task starts are the test; a refusal names the owner's uid and root's uid as seen here.

