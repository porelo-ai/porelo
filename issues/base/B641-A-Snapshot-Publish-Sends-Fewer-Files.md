# B641 — A Snapshot Publish Sends Fewer Files

```json
{
  "$schema": "issue-1.json",
  "id": "B641",
  "title": "A Snapshot Publish Sends Fewer Files",
  "state": "Implementing",
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

The operator's order leaves porelo-cpu-check out. porelo-planner takes it, so leaving it out must be confirmed with
Agent Planner first, or it stays published.

**What must be true.**
- In the snapshot channel, only the modules another repository resolves are published.
- A snapshot is published unsigned, if Central's snapshot repository takes unsigned files. That is confirmed first,
  and the issue says what confirmed it.
- A snapshot publishes no sources or javadoc jars.
- A release on a `v*` tag publishes as today: every opted-in module, signed, with sources and javadoc.
- The issue states the upload count before and after, measured from the publish's dry run (the deploy against a dead
  port that check-deploy reads).
