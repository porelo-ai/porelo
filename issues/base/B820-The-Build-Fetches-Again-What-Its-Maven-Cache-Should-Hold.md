# B820 — The Build Fetches Again What Its Maven Cache Should Hold

```json
{ "$schema": "issue-1.json", "id": "B820", "title": "The Build Fetches Again What Its Maven Cache Should Hold",
  "state": "Review", "size": "XS", "priority": "High", "rank": 143, "blockedBy": [],
  "covers": "The Maven cache is keyed by the poms' hash and never saved over an exact hit, so what porelo-parent or an imported BOM moves is fetched from Maven Central on every Build" }
```

**Why this size.** XS: the Porelo repositories' shared form of the Maven cache, copied; a change to `.github/` and
`BuildCachesTest`.

**What must be true.** A Build whose poms did not change fetches from Maven Central only what is new since its cache
was saved, so Central's rate limit cannot fail a Build.

## Found

Read in `.github/actions/pinned-jdk/action.yml` and the job log of Build 43583612326, attempt 1, "Build and unit
tests":

- The cache is `actions/cache` with `key: maven-${{ runner.os }}-${{ hashFiles('**/pom.xml') }}`. That job had an
  exact hit (`Cache hit for: maven-Linux-51b2781c…`), and `actions/cache` saves nothing after an exact hit.
- Versions come from `porelo-parent` and imported BOMs as well, which move with no pom here changing. After that exact
  hit the job still fetched release files from Central: `jackson-datatype-jsr310` 2.21.2 (pom and jar) and
  `jackson-modules-java8` 2.21.2, on every Build until a pom here changes.
- The metadata of `bcprov-jdk18on` and `bcutil-jdk18on` was asked 4 times each: `bcpkix-jdk18on` 1.80, which sshj
  brings into the published `porelo-machines` tool, declares them by a version range. That tool's own pom excludes
  them since its source changed, and its next published snapshot settles it; it is not this issue.
- Snapshots of Porelo's own repositories are asked again on purpose and are not this issue.

## Built

- The shared form, copied: `pinned-jdk` restores the local Maven repository with `actions/cache/restore` under a key that adds
  the run and its attempt to the poms' hash, falls back to the newest entry of the same poms and then to any, and
  touches a marker after the restore; its output `maven-cache-key` names the key.
- `.github/actions/maven-cache-save` (new) saves under that key, even after a failure, only when a jar or pom changed
  status after the marker (`find -cnewer`: Maven dates a download by the server's Last-Modified, so `-newer` finds
  nothing).
- "Build and unit tests" is the one job of a run that saves; the others restore only.
- `BuildCachesTest` takes each line of a multi-line `restore-keys` as a prefix the key must start with; it went red
  with a prefix that is not the key's, and on the form against the old single-prefix check.
- Green: `core` with `-am -Pwhole verify` (656 tests, 0 skipped), the YAML of the changed files parsed, the document
  checks. The first two Builds after the push are its proof.

## Acceptance

- Two Builds in a row with no pom change: the second job's log shows no "Downloading from central" of a release file
 .
