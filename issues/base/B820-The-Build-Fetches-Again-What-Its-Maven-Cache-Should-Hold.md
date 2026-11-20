# B820 — The Build Fetches Again What Its Maven Cache Should Hold

```json
{ "$schema": "issue-1.json", "id": "B820", "title": "The Build Fetches Again What Its Maven Cache Should Hold",
  "state": "Backlog", "size": "S", "priority": "High", "rank": 143, "blockedBy": [],
  "covers": "The Maven cache is keyed by the poms' hash and never saved over an exact hit, so what porelo-parent or an imported BOM moves is fetched from Maven Central on every Build" }
```

**Why this size.** S: the cache step of `.github/actions/pinned-jdk/action.yml`, a change to `.github/` with
`PublishWorkflowTest` and its neighbors following.

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

## Plan

- Save the cache under a key that changes when what was resolved changes: restore by the poms' hash as a prefix, and
  save after the build under a key that adds a hash of the local Maven repository's release directories, or with
  `actions/cache/save` whenever the restore was not an exact hit of the saved key; keep snapshots of Porelo's own
  repositories out of what is saved.
- Red first: `PublishWorkflowTest`'s pinned expectation of the cache step changes in the same commit; the measure is a
  Build after a parent move that downloads no release file from Central, read in its log.

## Acceptance

- Two Builds in a row with no pom change: the second job's log shows no "Downloading from central" of a release file
 .
