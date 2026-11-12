# B641 — A Snapshot Publish Sends Fewer Files

```json
{
  "$schema": "issue-1.json",
  "id": "B641",
  "title": "A Snapshot Publish Sends Fewer Files",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "porelo's snapshot publish sends to Central only the modules other repositories resolve, unsigned and without sources and javadoc jars, so the shared runners' uploads hit Central's 429 less often; a release on a v* tag publishes as before."
}
```

**Why this size.** The publish step's profile and the opted-in modules' properties, one workflow test, and the count
measured before and after. No production code changes.

Filed 2026-11-12 on the operator's order, after porelo's Publish on main failed twice with Central's snapshot
repository answering 429 Too Many Requests (for porelo-bom's maven-metadata.xml and for porelo-cpu-check's jar.asc).
The retry flags in the deploy line (626d2c1bb) make a throttled upload wait and try again. This issue cuts how many
uploads there are.

How it is today, read in the poms: eight modules opt into publishing with `skipPublishing=false`: wire, agents/api,
builds/api, ecosystems/api, bom, acceptance/kit, build/ffm-check and build/cpu-check. Each one publishes its pom, its
jar, a sources jar and a javadoc jar, each with a `.asc` signature, plus checksums and `maven-metadata.xml`. The
coordinator counted 105 to 140 uploads per publish.

What the other repositories resolve from porelo, read in their poms:
- the agent repositories (pi, codex, claude-code, agy, omp): porelo-agent-api, porelo-wire, porelo-acceptance-kit,
  porelo-bom;
- porelo-adapters: porelo-build-api, porelo-wire, porelo-bom;
- porelo-ecosystems: porelo-ecosystem-api;
- porelo-planner: porelo-cpu-check, at `${porelo.buildtools.version}`, for its native image's check.

The operator's order leaves porelo-cpu-check out. porelo-planner takes it, but at `porelo.buildtools.version`, which
porelo-parent sets to 0.5.2-SNAPSHOT, while porelo publishes it as 0.5.1-SNAPSHOT. No repository resolves porelo's
snapshot of either check, so both stay out of a snapshot and are kept in a release.

**What must be true.**
- In the snapshot channel, only the modules another repository resolves are published.
- A snapshot is published unsigned, if Central's snapshot repository takes unsigned files. That is confirmed first,
  and the issue says what confirmed it.
- A snapshot publishes no sources or javadoc jars.
- A release on a `v*` tag publishes as today: every opted-in module, signed, with sources and javadoc.
- The issue states the upload count before and after, measured from the publish's dry run (the deploy against a dead
  port that check-deploy reads).

## As built

- Central validates no snapshot. Its Publish Portal Snapshots page says: "As with the legacy OSSRH `-SNAPSHOT`
  publishing, no validation is performed." So a snapshot goes unsigned and without sources and javadoc jars.
- Publish's "Deploy the jars" step reads `CHANNEL` (the "Which channel" step's output). For `snapshots` it adds
  `-Dgpg.skip=true -Dmaven.source.skip=true -Dmaven.javadoc.skip=true -DskipInternalPublishing=true`. A release adds
  nothing and publishes as before. The retry flags stay on both.
- `build/cpu-check` and `build/ffm-check` take `skipPublishing` and `maven.deploy.skip` from `skipInternalPublishing`,
  which defaults to `false`, so a release still publishes them.
- `PublishWorkflowTest` runs the step for both channels and expects the snapshot's arguments, and the step's env names
  `CHANNEL`. Without the change it failed for every snapshot row and for the env.
- Measured with the publish's own dry run (deploy with `-Pcentral-sonatype-release` against a dead port) and counted in
  `target/central-deferred`, where the publishing plugin stages what it uploads:
  - before (245fe2c98): 8 artifacts and 36 files (8 poms, 7 jars, 7 sources jars, 7 javadoc jars, 7 CycloneDX
    files). With a `.asc` for each, that is 72 files, plus 16 metadata files.
  - after: 6 artifacts (porelo-wire, porelo-agent-api, porelo-build-api, porelo-ecosystem-api, porelo-bom,
    porelo-acceptance-kit) and 16 files (6 poms, 5 jars, 5 CycloneDX files), unsigned, plus 12 metadata files.
  - So 88 files become 28, about a third. Checksums and requests scale with the files, so the 105 to 140 uploads per
    publish the coordinator counted should become about 33 to 45. The next snapshot publish is the measurement.

## After the code reading

Read by agy (gemini-4-argon-high).

- Green: a release publishes as before, and the snapshot's arguments cannot reach it. The skip flags match the
  parent's plugins (`maven.source.skip`, `maven.javadoc.skip`, `gpg.skip`). Nothing else in the job depends on the
  snapshot's sources, javadoc, signatures or the two checks. The step's shell is safe with `set -eu`, an unset
  `CHANNEL` and `forge-release`.
- Taken (yellow): no test showed which modules each channel publishes. Hard-coding `skipPublishing` back to `false` in
  a check would have stayed green.
  `aSnapshotPublishesOnlyWhatOtherRepositoriesResolveAndAReleaseEverything` reads the modules' poms and expects the
  six for a snapshot and the eight for a release. It failed when cpu-check's `skipPublishing` was set to `false`.
- Noted: "Import the signing key" still runs for a snapshot, though nothing in it signs now. It is left as it is,
  because the step serves the packages' channel as well.
