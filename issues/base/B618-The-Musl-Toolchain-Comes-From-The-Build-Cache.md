# B618 — The Musl Toolchain Comes From The Build Cache

```json
{
  "$schema": "issue-1.json",
  "id": "B618",
  "title": "The Musl Toolchain Comes From The Build Cache",
  "state": "Backlog",
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

## Follow-up: the package check's apt and dnf

Today's publish of 77ed2c9e9 failed in the package check (`dist/package-check`, `exec:java@package-check`), not in
the musl job: "Ubuntu 26.04 ended at its limit on azure.archive.ubuntu.com during apt-get update". The plan above does
not reach it, since the check runs `apt-get update` and `apt-get install` (Ubuntu, Debian) and `dnf install` (Fedora,
RHEL-likes) inside its containers against the live archives on every run.

- The check mounts one cache directory per image into its container: apt's lists and archives
  (`/var/lib/apt/lists`, `/var/cache/apt/archives`, with `Binary::apt::APT::Keep-Downloaded-Packages "true"`) and
  dnf's cache (`keepcache=1`). The build restores and saves that directory with `actions/cache`, keyed by the image's
  pinned digest and the UTC date.
- `apt-get update` still runs, so the check installs against today's archive. Where it fails and the restored lists
  are from a successful update of the same image digest, the check goes on with those lists and says so in its log;
  without restored lists it fails as today. dnf the same with `--cacheonly` on a failed metadata refresh.
- Retention: only the newest entry per image is kept. After saving, the build deletes that image's older entries
  (`gh cache delete`), as for the musl, GraalVM, shfmt, wrapper and Maven caches above.
- Red first: a `package-check` unit test whose stand-in `apt-get update` fails, with restored lists present, answers
  "using the cached lists" and goes on; with none, it fails with "apt-get update" as today.

## Open questions

- Where the digest key comes from: the installer and its pins live in porelo-machines, so the key is read from
  `machines musl --pins` (a new option there) or pinned twice. Asked of Machines.

## Acceptance

- A second build run on one commit fetches nothing from musl.cc or zlib.net; the cache list holds one entry per cache.
