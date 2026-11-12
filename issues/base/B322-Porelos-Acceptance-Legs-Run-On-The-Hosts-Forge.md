# B322 — Porelo's Acceptance Legs Run On The Host's Forge

```json
{
  "$schema": "issue-1.json",
  "id": "B322",
  "title": "Porelo's Acceptance Legs Run On The Host's Forge",
  "state": "Analyzed",
  "size": "M",
  "priority": "Medium",
  "rank": 42,
  "blockedBy": [],
  "covers": "porelo's acceptance legs run on the host's Forgejo as well, each on a machine asked for by kind from the host's pool, and the binary published there is again the one the Ubuntu leg tested",
  "openQuestions": 3
}
```

**Size.** M: porelo's leg driver and `build.yml`, and the workflow test that pins it; the pool and its variable are
porelo-host's ([HO27](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho27)) and porelo-buildtools'. Analyzed 2026-11-12 (ST40).

Waits for machines asked for by kind from the host's pool, rented from a workflow on the host's
Forgejo; until then porelo's legs run only on the central forge.

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

## Plan

Read in porelo's code on 2026-11-12. `acceptance/legs` `Main.leg` opens `Hetzner` and passes it to `Leg.run` as a
`Machines` (`Leg.java:156`). porelo-buildtools already has the pool as a `Machines`: `LocalPool implements Machines`,
with `Pool.read(file)`, `acquire(Spec)` and `Kind` (`amd64`, `amd64-build`). Its acquire hands out a run account on a
VM that resets itself when given back, so `sweep --mine` has nothing to delete there.
1. **Red first.** A `legs` `MainTest`: with `PORELO_MACHINES_POOL` naming a pool file, `leg --os ubuntu` acquires from
   the pool by kind `amd64-build` (four accounts) and never opens Hetzner. Without the variable it rents as today. In
   core `PublishWorkflowTest`, the `legs` job carries no `github.server_url` condition, and Publish's `needs` stays
   `[build, proven, legs, native]` on both forges.
2. **The source.** `Main.leg` picks `LocalPool.onThisHost(Pool.read(...))` when the variable is set and not empty,
   else Hetzner. `Leg.run` asks the `Machines` for a lease by OS and kind, not by server type. `--type` and
   `vars.LEG_TYPES` stay Hetzner's.
3. **The workflow.** `build.yml` drops `github.server_url == 'https://github.com'` from `legs`. The HETZNER secrets stay
   in the env, empty on the forge. The clean-up step stays: `sweep --mine` does nothing for a pool lease (HO27's
   buildtools part).
4. **Publish.** It is unchanged. It already waits for `legs` or for a proof and `native`, and packages the binaries the
   Ubuntu leg fetched back.
5. **Order.** It is built after HO27 and its buildtools part are released, and the root pom's
   `porelo.buildtools.version` is raised in the same commit.

## Acceptance

- The `legs` and workflow tests above are seen red first.
- A run on the host's forge (Molecule, forge and pool on) runs both legs from the pool. The binary Publish packages is
  the Ubuntu leg's. The same commit with no edit runs both legs rented on GitHub.

## Open questions

1. **Reboot scenarios on a pool VM.** The suite's `@restart` scenarios reboot the machine. On the pool that is a VM
   the host resets afterwards. May a leg reboot a pool VM, or are those scenarios left to the GitHub legs?
2. **A Fedora VM in the pool.** The Fedora leg needs a Fedora VM in the host's pool, beside Ubuntu. Should the host
   keep one, at its cost in memory and disk, or does the forge run only the Ubuntu leg?
3. **Which forge publishes.** When both forges run the same commit, both reach Publish. Should the host's forge publish
   to Central and the releases as well, or only build and prove, with publishing kept on one forge?

## To be checked

- How many machines the pool holds, and whether two legs and another repository's legs may wait for one.
- What a pool machine reaches: the forge's registry for the snapshots the suite resolves, and Central.
