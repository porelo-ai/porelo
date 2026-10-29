# B306 — Porelo's Build Runs On The Local Forge

**Status:** Idea.

**What must be true.** porelo's workflows run green on the host's Forgejo and on the central forge on the same commit,
with no edit between them, with the values of porelo-host's
`doc/local-forge.md`, *The local registry and publication settings*.

## What is in the way today

- **The legs, off on the host's forge for now:** `build.yml`'s `legs` job (*Leg - fedora*, *Leg - ubuntu*, each with
  *Delete what this run created*) rents a machine per OS from the provider. It runs only where `github.server_url` is
  GitHub's, decided by the operator on 2026-10-29: a machine asked for by kind from the host's pool does not exist
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
