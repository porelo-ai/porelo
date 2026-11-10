# [B557](README.md#b557) — Root As Seen In A Hook's Namespace Is Not Its Own

**Status:** Review. Filed 2026-11-09 from agy's reading of [B540](README.md#b540); for after the move.
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
  owner. Otherwise take it only where no line of the namespace's map maps any inside uid to outside uid 0 and the
  path is one the code names, including the real rootless podman map's uid and subuid lines.
- `rootAsSeenHere`: refuse a map whose line for inside uid 0 does not show outside uid 0 of the initial namespace.
- Tests on the map texts: initial, rootless podman's, nested, and unmapped root.

## As built

The nft, reader and supervisor hooks need distribution programs whose owners read as the overflow uid under
rootless podman. `SystemProgram` permits that owner only for normalized programs directly in `/usr/sbin`,
`/usr/bin`, `/sbin` or `/bin`, with both the requested path and its resolved target checked, along with every
ancestor's owner and permissions. No line of the map may map any inside uid to outside uid 0. This permits the
real multi-line rootless map (`0 <uid> 1` and the subuid range); it does not require exactly one line. Custom
paths, descendants of those directories and links escaping them are refused. This is a fixed-path exception:
the overflow uid itself cannot distinguish root from another unmapped account.

Only the complete initial-namespace map `0 0 4294967295` establishes uid 0 as the host's root, following the
existing host namespace check's criterion. A nested `0 0 1` names its parent and proves no such identity, so it
is refused, as is a map with outside uid 0 mapped to any other inside uid. Every map line is validated before
either case is taken; empty, malformed, overlapping or out-of-range maps and a nonpositive rootless overflow uid
are refused with an explanation.

No `RootOwnedFile` caller needs unmapped ownership. Data reads, path checks and open-descriptor reads all refuse
it, even under a distribution directory, because another unmapped account has the same numeric owner. Explicit
attribute-source overloads remain trusted caller fixtures; their production callers supply literal root uid 0,
and this change does not add namespace discovery to those overloads.

Unit fixtures cover the initial map, the real multi-line rootless map, nested and nonzero root mappings, another
unmapped account's executable and data, directory and resolved-link escapes, and uncertain maps. Refusal cases
failed on the old behavior; the initial and rootless acceptance cases failed under a deliberate uid-mapping
mutation before the fix. A clean focused run passed all 61 cases without skips.
