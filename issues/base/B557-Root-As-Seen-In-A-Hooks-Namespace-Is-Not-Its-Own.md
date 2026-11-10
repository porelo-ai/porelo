# [B557](README.md#b557) — Root As Seen In A Hook's Namespace Is Not Its Own

**Status:** Implementing, since 2026-11-10. Filed 2026-11-09 from agy's reading of [B540](README.md#b540); for after the move.
**Size:** S - one check where `wire` decides who root is, with its tests.
**Blocked by:** -.

**What must be true.** `SystemProgram` and `RootOwnedFile` take a file as root's only when its owner can be nobody
else. In a user namespace where the host's root is unmapped, an owner that reads as the overflow uid is not proof of
root, since every other unmapped owner reads as that uid too. A namespace whose `uid_map` maps uid 0 to anything
other than the host's root (a nested namespace can read `0 0 1`) does not count as root either. Where the owner
cannot be told, the file is refused as unsafe, never taken.

## Why

`SystemProgram.rootAsSeenHere` returns `/proc/sys/kernel/overflowuid` when the host's uid 0 is unmapped: the case of
a hook in rootless podman's namespace. A file of another unmapped account then passes as root's. In a nested
namespace, `/proc/self/uid_map` names the parent namespace, so `0 0 1` makes the caller itself "root". The paths these
checks take today are fixed system paths under root's own directories, so no way to use it is known. A path under
another account's directory would be the first. `porelo-host` refuses to run outside the host's own namespace
since [B540](README.md#b540), so this concerns `porelo`, `porelod` and the hooks.

## Plan

- Find whether any hook or program needs a file whose owner reads as the overflow uid. If none does, refuse that
  owner. Otherwise take it only where the namespace's single map line shows the host's root unmapped and the path is
  one the code names.
- `rootAsSeenHere`: refuse a map whose line for inside uid 0 does not show outside uid 0 of the initial namespace.
- Tests on the map texts: initial, rootless podman's, nested, and unmapped root.
