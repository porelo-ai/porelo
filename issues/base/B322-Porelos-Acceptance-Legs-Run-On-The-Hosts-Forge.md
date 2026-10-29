# B322 — Porelo's Acceptance Legs Run On The Host's Forge

**Status:** Idea. Waits for machines asked for by kind from the host's pool, rented from a workflow on the host's
Forgejo; until then porelo's legs run only on the central forge, decided by the operator on 2026-10-29.

**What must be true.** porelo's acceptance legs run on the host's Forgejo as they do on the central forge, on the same
commit with no edit between them, and the binary published there is the one the Ubuntu leg tested.

## What it switches on again

- **The job:** `build.yml`'s `legs` job, *Leg - fedora* and *Leg - ubuntu*, each with its *Delete what this run
  created*. [B306](README.md#b306) lets it run only where `github.server_url` is GitHub's; this issue drops that
  condition.
- **Publish:** on the host's forge, `deploy` again waits for the legs, or for a proof and the binaries built on the
  runner, as on the central forge, and packages the binaries the Ubuntu leg fetched back with a manifest.

## What changes for that

- **How a leg gets its machine:** today `acceptance/legs` rents a server type per OS (`leg --os ...`, `vars.LEG_TYPES`)
  and the clean-up sweeps the run's own machines (`porelo-machines sweep --mine`). On the host's forge a leg asks the
  host's pool for a machine of a kind and hands it back when it ends; no provider and no provider credential is named in
  the workflow.
- **The acceptance suite's needs on that machine:** podman and the accounts it creates (`--accounts 4`), as on a rented
  leg today.

## To be checked

- How many machines the pool holds, and whether two legs and another repository's legs may wait for one.
- What a pool machine reaches: the forge's registry for the snapshots the suite resolves, and Central.
