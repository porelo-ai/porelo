# B235 — A Dry Run Shows The Agent's Refusals

**Status:** Implementing.
**Size:** S - `TaskLaunch`'s dry run and one guard in `EgressReport`, tests in `app`; no contract.

**What must be true.** `porelo task start --dry-run` names a host the task's agent refuses as refused, never as
reachable, so what the dry run says is what the running task enforces.

## Why

Measured on `0.5.1~snapshot.307`: `task start --dry-run --agent claude` lists `raw.githubusercontent.com` (from the
set `git-hosting`) and `downloads.claude.ai` (from the project) as reachable, exactly as with `--agent stub`, while the
running task with that agent refuses both: an agent's refused domains win over the project's, and a lookup there
answers NXDOMAIN. Someone reading the dry run to plan a build learns the opposite of what happens.

## Plan

Read in `TaskLaunch`, `EgressReport`, `AgentChoice`, `EgressControl` and `DnsPolicy`.

1. **Today the dry run returns before an agent is chosen** and reports the project's hosts with no refusals at all
   (`TaskLaunch`'s dry-run branch passes `Map.of()` to `EgressReport.reportReachable`), so not even the project's own
   `egress.refused` reaches it. Read, not measured: the running start's report lists a refused host under
   "reachable" too, then again as "refused on purpose", since `reportReachable` prints every origin unfiltered.
2. **`EgressReport.reportReachable`** skips an origin whose host is refused by its own name. Not one under a refused
   name: dnsmasq takes the longest match, so a host allowed by its own, longer name under a refused one is reached,
   and the report says so.
3. **The dry run chooses the agent as a start does** (`AgentChoice.considerAgent` with `--agent`) and reports
   `EgressReport.refusals(agent, project, repository)`, the same call the start makes.
4. **Red first:** `EgressReportTest.aRefusedHostIsNotAlsoListedAsReachable`, then
   `TaskRunCommandTest.aDryRunNamesAHostTheProjectRefusesAsRefusedAndNotAsReachable` (a project naming and refusing
   the same host, so no installed agent is needed).

**Decided:**
- The dry run shows the whole picture: the agent's and the provider's hosts too, as the start enforces them.
- With several agents installed and no `--agent`, it previews without an agent and says in one line that `--agent` names one; it asks nothing.

## Acceptance

- `task start --dry-run --agent claude` on a project naming `raw.githubusercontent.com` lists it as refused by the
  agent and not as reachable. Seen to fail first on today's code.
- A host the project both names and refuses reads refused in the dry run and in the start's own report.
- The daemon's `Egress` answer leaves a refused host out of `hosts` too, so an interface shows what the CLI does.
- A provider the dry run cannot use is refused in one line with 2, as the start refuses it.

## Checked

- **Opening the installed agents in a dry run starts each agent's adapter** to read its definition, as `porelo
  agents` does; it touches no container runtime and starts no session. Measured in
  `TaskRunCommandTest.aDryRunIncludesTheChosenAgentsRefusalsAndItsProvidersHosts`: no runtime call, no exec, and no
  socket left in the runtime directory after.
