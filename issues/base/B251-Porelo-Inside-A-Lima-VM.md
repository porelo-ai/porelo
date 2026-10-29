# B251 — Porelo Inside a Lima VM

**Status:** Idea.

**What must be true.** Porelo runs its tasks inside a Linux VM that Lima starts on a Mac, with the task firewall
loaded and failing closed, or says before the first start what the VM lacks for it.

## Why

From the Mac probe of 2026-10-22: in a Lima VM (Ubuntu 26.04.1
aarch64, kernel 7.0.0-34, vz with Rosetta) rootless podman ran, but `unshare` into a user and network namespace for
nftables failed with `unshare: write failed /proc/self/uid_map: Operation not permitted`. Read, not measured: Ubuntu's
restriction of unprivileged user namespaces (`kernel.apparmor_restrict_unprivileged_userns`).

On this project's Ubuntu 26.04 VM the task firewall loads, and on the development machine a plain `unshare --user
--map-root-user --net` works with `kernel.apparmor_restrict_unprivileged_userns=1`
([B220](README.md#b220)). So something differs.

## To be checked

- Whether porelo's task firewall meets the same refusal in that VM: its hooks enter the container's namespace
  through `podman unshare nsenter --net`, which maps through podman's setuid `newuidmap` rather than a plain
  `unshare`'s own write to `uid_map`.
- What differs from this project's Ubuntu 26.04 VM: the sysctls `kernel.apparmor_restrict_unprivileged_userns` and
  `kernel.unprivileged_userns_clone`, the AppArmor profiles loaded, `/etc/subuid`, the kernel's flavour.
- What Porelo would need there, and whether `porelo doctor` should name it before a start.
