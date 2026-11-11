# B618 — The Musl Toolchain Comes From The Build Cache

```json
{
  "$schema": "issue-1.json",
  "id": "B618",
  "title": "The Musl Toolchain Comes From The Build Cache",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The build's musl job takes the musl toolchain and zlib from the Actions cache, keyed by their pinned digests, and the build keeps only the newest entry of each of its caches."
}
```

**Why this size.** one cache step in `build.yml` around the musl install, one pruning step for the caches the build
saves; no code, no contract.

Filed 2026-11-11 as porelo's part of `porelo-project` ST317, "Builds take outside downloads from a cache".

**What must be true.** Every download from outside GitHub that a build of porelo makes comes from the Actions cache
after its first run, and the cache holds only the newest state of each.

How it is today, read in the workflows:

- `pinned-jdk` already keeps GraalVM and shfmt by their digests, the wrapper's Maven by its properties, and the Maven
  repository by the poms' hash.
- The musl job's step "The musl toolchain" runs `machines musl`, whose installer fetches
  `https://musl.cc/x86_64-linux-musl-native.tgz` and zlib from `https://zlib.net/fossils/` on every run, with no cache.
- The Maven repository's cache is saved under a new key at every pom change and restored by its prefix; the older
  entries stay until GitHub evicts them (an entry unused for 7 days, or the oldest beyond the repository's limit).

## Plan

- Restore the musl prefix (`MUSL_PREFIX`, by default a directory in the build user's home) keyed by the installer's
  pinned digests of the toolchain and zlib; run `machines musl` only on a miss, then save.
- After saving a new entry, the build deletes the older entries of the same cache (`gh cache delete` on each key with
  the same prefix and another digest), for the musl, GraalVM, shfmt, wrapper and Maven caches.
- `check-actions` passes; a run after the first shows the musl step restored, not fetched.

## Open questions

- Where the digest key comes from: the installer and its pins live in porelo-machines, so the key is read from
  `machines musl --pins` (a new option there) or pinned twice. Asked of Machines.

## Acceptance

- A second build run on one commit fetches nothing from musl.cc or zlib.net; the cache list holds one entry per cache.
