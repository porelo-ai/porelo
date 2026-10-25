# B285 — A Snapshot Deployed To The Local Forge

**Status:** Review.
**Size:** S - in `porelo-parent` only: one profile, one server in `settings.xml`, the Publish step and one test script;
the names it reads are already fixed in porelo-project [PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60).

**What must be true.** The same `build.yml` publishes porelo-parent's snapshot on both forges, choosing only by the
forge's variables: to Sonatype under `central-sonatype-release` on GitHub, exactly as before, and to the local forge's
Maven registry under a second profile, `forge-release`, unsigned and with no Central plugin. Every repository built on
the parent inherits that profile, so its own deploy step needs nothing but the same variables.

## Plan

- **`pom.xml`, profile `forge-release`:** `distributionManagement` with only a `snapshotRepository`, id `porelo-forge`,
  at `${env.MAVEN_SNAPSHOTS_URL}`, so `maven-deploy-plugin` 3.1.4 (already managed) deploys there and a release version
  is refused. `maven.deploy.skip` follows `skipPublishing`, so a module opts in with the one switch it already sets
  for Central. An enforcer execution fails the build, naming the variable, when `MAVEN_SNAPSHOTS_URL` or
  `PORELO_PUBLISHER_TOKEN` is unset. No GPG plugin and no `central-publishing-maven-plugin` in the profile.
- **`settings.xml`:** server `porelo-forge`, user `porelo-publisher`, password `${env.PORELO_PUBLISHER_TOKEN}`. The
  repositories that read the forge's snapshots (porelo-buildtools, porelo-message-sluice) use the same id.
- **`build.yml` Publish:** `deploy -P"$PORELO_DEPLOY_PROFILE"` with the variables passed through the step's environment,
  refused unless it is `central-sonatype-release` or `forge-release`; the Sonatype secrets and the BouncyCastle signer
  stay as they are.
- **Red first:** `test/child-deploys-to-forge.sh`, a step of the Build job: a child that opts in deploys its pom to a
  `file://` registry with no `.asc`; a child that does not opt in deploys nothing; a deploy without
  `MAVEN_SNAPSHOTS_URL` fails with the profile's own message. Against today's pom the first check fails, since an
  unknown profile only warns and nothing is deployed.

## Acceptance

- The Build job green with the new step; each of its three checks seen red once by breaking the profile.
- `help:effective-pom -Pcentral-sonatype-release` on the change differs from `main` only by listing the new profile.
- The parent itself deployed under `forge-release` to a `file://` registry: one pom, no signature.
- The GitHub Build after the push publishes the snapshot to Sonatype as before, with
  `vars.PORELO_DEPLOY_PROFILE=central-sonatype-release`.
