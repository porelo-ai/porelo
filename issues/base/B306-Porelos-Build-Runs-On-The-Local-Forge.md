# B306 — Porelo's Build Runs On The Local Forge

**Status:** Accepted.
**Size:** M, proposed - the workflows' conditions and the deploy step here; what only a forge shows is measured on one.

**What must be true.** porelo's workflows run green on the host's Forgejo and on the central forge on the same commit,
with no edit between them, with the values of porelo-host's
`doc/local-forge.md`, *The local registry and publication settings*.

## What is in the way today

- **The legs, off on the host's forge for now:** `build.yml`'s `legs` job (*Leg - fedora*, *Leg - ubuntu*, each with
  *Delete what this run created*) rents a machine per OS from the provider. It runs only where `github.server_url` is
  GitHub's: a machine asked for by kind from the host's pool does not exist
  yet. [B322](README.md#b322) switches it on again on the host's forge.
- **Publish without the legs:** `deploy` runs today only after the legs passed, or after a proof and the binaries built
  on the runner (`native`). On the host's forge it must run after the Build alone, so the snapshots reach the forge's
  registry; there the packages are not uploaded (`PORELO_PUBLISH`), and what the packaging steps need without a leg's
  binaries is settled here.
- **The other workflows that name the provider:** `sweep.yml` passes `HETZNER_API` to `sweep --now`, and
  `artifactory-smoke.yml` rents a machine and uploads to Artifactory; `delete-runs.yml` calls GitHub's API.
- **Publishing:** the Maven deploy is switched between Central and the forge's Maven registry by the deploy profile
  `PORELO_DEPLOY_PROFILE` names, and the Artifactory uploads of the packages stay off on the local forge by
  `PORELO_PUBLISH`, each a variable present on both forges, never a branch in the workflow.
- **The agent repositories build against what this Build deploys there:** porelo's snapshots (the
  `porelo-acceptance-kit` among them) reach the forge's registry only from porelo's own Build on that forge, never
  copied from Sonatype, so a change made in a task can be built against before it reaches GitHub.
- **What holds on the central forge:** the published binary is the one the Ubuntu leg tested, fetched back with a
  manifest.

## Plan

Read in the code on 2026-11-04. None of *What is in the way today* is built, all of it still to do:

- **Legs:** `build.yml` holds no `github.server_url`; `proven` (it asks about GitHub's runs), `legs` and the step *Stop the
  legs of a commit that failed here* (`gh run cancel`) run on any forge.
- **Publish:** `deploy` needs `legs` or a proof with `native`, so with the legs off it is skipped; its binaries come from
  an artifact, and *Deploy the jars* names `central-sonatype-release` outright.
- **Other workflows:** `sweep.yml` runs hourly with `HETZNER_API`; neither it, `artifactory-smoke.yml` nor
  `delete-runs.yml` reads `PORELO_PUBLISH`.
- **The variables:** no workflow here reads `PORELO_PUBLISH`, `PORELO_DEPLOY_PROFILE` or `MAVEN_SNAPSHOTS_URL`, and this
  `settings.xml` has no `forge` profile or server; `porelo-buildtools`' workflow reads all three.

Where snapshots come from, decided 2026-11-04: in principle from Sonatype or the central Artifactory; the host's registry
holds only what was built locally and has not yet gone through push and Build on the central forge. Both are read and the
newest wins. So an empty registry builds at once, nothing is copied from Sonatype, and the agent repositories wait on this
Build only for a change of porelo made on the host. How a project names its central source, the read order and the filter
that keeps a private project's names from being asked of Sonatype are porelo-buildtools' to design.

Steps:

1. **Off the forge what cannot run there:** `proven`, `legs` and *Stop the legs* only where `github.server_url` is GitHub's;
   `sweep.yml`, `artifactory-smoke.yml` and `delete-runs.yml` only where `PORELO_PUBLISH` is not `false`.
2. **Publish after the Build alone where nothing is uploaded:** on `PORELO_PUBLISH=false` `deploy` runs after `build`, and
   its binaries, packages, package check and uploads are skipped: no package leaves that forge, and the central forge still
   checks every package before it is published. *Deploy the jars* takes `PORELO_DEPLOY_PROFILE`, refusing an empty value as
   `porelo-buildtools` does. The central forge sets it to `central-sonatype-release`.
   Only `main` and tags deploy, as today, so no task branch's snapshot can be the newest.
3. **`settings.xml`,** with what `porelo-buildtools`' design says: a server for the forge's registry and the read of both.
4. **On a host's forge:** a commit green there and on the central forge with no edit between.

## Open questions

- `settings.xml`: waits on that design, and so does step 2's deploy reaching the registry.

## To be checked

- What only a forge shows: the build job's FFM step and `-U` reads through the runner's proxy, and the time a Build takes on
  the host. Nothing of steps 1 and 2 needs an artifact on the forge, since `native` and the legs do not run there.
