# B618 — The Musl Toolchain Comes From The Build Cache

```json
{
  "$schema": "issue-1.json",
  "id": "B618",
  "title": "The Musl Toolchain Comes From The Build Cache",
  "state": "Review",
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

## As built

The package check's apt and dnf were built first (the follow-up above); then the musl part and the retention.

- **The musl job restores `x86_64-linux-musl-native`** in the build user's local `opt` folder, where the pom's
  `musl.home` looks, with `actions/cache/restore`, keyed by the runner's OS and the two digests the installer pins
  (the musl.cc toolchain, then the zlib.net zlib). `machines musl` runs only on a miss, and the prefix is saved right
  after it, so a failed install is never saved. The installer resolved today (porelo-machines 0.5.2-SNAPSHOT) carries
  the same two digests.
- **The digests are written in the workflow a second time.** `machines musl` takes no option and names no digest, so
  there is nothing to read them from; a moved pin in porelo-machines is taken up only when the workflow's key moves
  with it, and until then the restored toolchain is the old one. Reading the key from the installer
  (`machines musl --pins` or the like) waits on Machines and is not asked of it here.
- **Retention is by key, not by deletion.** Every cache the build keeps names what it holds: the musl, GraalVM and shfmt
  entries their digests, the wrapper its properties' hash, the Maven repository the poms' hash, the package check a
  day. A moved pin saves under a new key and the old entry is no longer read; the two with `restore-keys` (Maven,
  package check) read the newest entry under the prefix. GitHub drops an entry seven days after its last read, so no
  `gh cache delete` step and no `actions: write` was needed.
- **`BuildCachesTest`** holds the musl steps' order, condition, key and path, and that every cache step in the build and
  in `pinned-jdk` keys by a digest, a hash or a step's output, with `restore-keys` only as the key's own prefix.

Not run: a build on GitHub, so "a second run fetches nothing" is seen on the first two runs after the push.

## After the code reading

agy (gemini) read the change. Fixed: `BuildCachesTest` is tagged `documents`, so the shared rules check runs it on a
change to `.github/` alone, which starts no build; its assertions each state their reason; the changelog no longer says
a moved digest is fetched by itself. Left as said: the digests stand in the workflow a second time, so a moved pin in
porelo-machines is taken up only with the workflow's key, and a miss in between saves the newer install under the old
key; both installs are checked against the installer's own pins, so nothing unchecked is restored. The test's patterns
compiled inline stay, as they run once per test.

## Acceptance

- A second build run on one commit fetches nothing from musl.cc or zlib.net; the cache list holds one entry per cache.
