# B516 — Task Records Exported Over OTLP

```json
{
  "$schema": "issue-1.json",
  "id": "B516",
  "title": "Task Records Exported Over OTLP",
  "state": "Idea",
  "size": "M",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": ["B514"],
  "covers": "[B514](#b514)'s records exported as OpenTelemetry logs to a collector the organization names, off by default, chosen per kind, by the CLI only."
}
```

**Why this size.** an OTLP log exporter on [B514](README.md#b514)'s one stream, its settings, and a choice per kind of record.

Split off [B514](README.md#b514) on 2026-11-08 at the operator's word (15:23Z); not built until the
operator says so.

**What must be true.** The records of [B514](README.md#b514)'s stream can be sent as OpenTelemetry logs over OTLP to a collector the
organization names.
- It is off by default.
- What may leave is the person's choice per kind of record.
- The agent's own output (code, prompts) and anything the vault or the gate held never leaves unless chosen. Secrets
  are never in a record at all, as today.
- It is chosen by the CLI only; the window does not offer it (the operator, 2026-11-08 14:27Z).

## Why

[B514](README.md#b514) makes one stream of every Porelo process's records. An organization that runs a collector wants them there too.
The export crosses a trust boundary of its own, so it is its own issue and its own decision.

## Plan

Not planned yet. A plan reading comes first: what leaves, and to whom, is the trust boundary.

## Plan inputs (2026-11-12)

From the operator's research on AI agent observability tools (`porelo-project` ST341):
- **The export is a derived view of [B514](README.md#b514)'s journal.** It is never a second store, and the journal
  answers whatever an export lost.
- **Standard parts, not our own protocol:** the OpenTelemetry SDK and its OTLP exporter.
  - Lifecycle entries and log lines go out as LogRecords.
  - Finished steps can go out as spans.
  - Numbers go out as metrics.
- **Attributes:** `porelo.task.id`, `porelo.run.id`, the record's sequence number and its kind, plus the fitting
  `gen_ai.*` fields. The GenAI semantic conventions are still in development (now in
  open-telemetry/semantic-conventions-genai), so the version used is pinned and named.
- **An allowlist of fields before the first hop.** Off by default: free text, prompts, tool arguments and results, the
  agent's output and error texts. A secret is never released by any "allow output" switch, because it is never in a
  record.
- **Delivery:**
  - an export cursor in the journal, with retry and dedup;
  - backpressure limits, and gaps shown, never hidden;
  - an export that fails never blocks or slows a run.

Open questions, for the plan reading:
- Future records only, or released history as well?
- How do duplicates and lost records show, at the destination and in porelo?
- Only permissively licensed open-source destinations, or AGPL and ELv2 ones too, each as a separate target?
