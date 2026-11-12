# B644 — An Agent's Own Telemetry Kept With Its Run

```json
{
  "$schema": "issue-1.json",
  "id": "B644",
  "title": "An Agent's Own Telemetry Kept With Its Run",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 84,
  "blockedBy": ["B514"],
  "covers": "porelo receives an agent's own OTLP export on a local receiver and keeps it in the task's journal under its run, vendor ids mapped and sources marked, tokens and costs never counted twice; nothing is forwarded unless the OTLP export chooses it."
}
```

Filed 2026-11-12 as `porelo-project` ST342's porelo part, from the operator's research on AI agent observability
tools. The adapters' part (each adapter pointing its agent's native OpenTelemetry export at this receiver, with every
content switch set off) is filed in the adapters' repositories.

**Why.** Claude Code, Codex and omp can export their own telemetry over OTLP: their turns, tool calls, tokens and
errors, as they see them. pi can do so through extensions. porelo sees a task from outside: its lifecycle, its gate,
and its provider requests. Kept together under the run, the two show what happened inside and outside the agent. Sent
to a vendor or a collector directly, they would bypass [B514](README.md#b514)'s journal and
[B516](README.md#b516)'s allowlist.

**What must be true.**
- porelo offers each task a local OTLP receiver that only that task's agent can reach. It is never a port on the host.
- What arrives is kept in the task's journal under its run ([B514](README.md#b514)), marked with its source (the
  agent's own word, not porelo's measurement). The vendor's ids (session, conversation, trace) are mapped to the task
  and run, not taken as porelo's.
- Tokens and costs are counted once. porelo's own count from the provider requests stays the figure. An agent's
  figure is kept beside it, never added to it. A cost is marked as an estimate with the date of its price.
- Content the agent sends despite its switches (prompts, tool output) is held to the same rules as porelo's own
  records. It is not forwarded and not shown by default.
- Nothing leaves the machine unless [B516](README.md#b516)'s export chooses it.

Open questions, for the plan reading:
- Logs, traces and metrics all, or logs and metrics first?
- What the receiver does with a record it cannot map to a run.
- The disk budget, shared with [B514](README.md#b514)'s.
