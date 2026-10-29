# [B292](README.md#b292) — Hardened SARIF Reading On java-sarif

**Status:** Idea.

**What must be true.** Every Java review reader and writer uses `com.contrastsecurity:java-sarif` 2.0 with a
maintained Jackson 2.x dependency set and limits enforced before model binding. Scanner imports run in a bounded
worker and become profile documents with explicit completeness, so hostile or unfinished input cannot exhaust the
daemon or appear clean.

## Proposed

- Blocked by: [B291](README.md#b291).
- Blocks: [B166](README.md#b166), [B171](README.md#b171), [B172](README.md#b172), [B141](README.md#b141),
  [B95](README.md#b95); the message filter, the forge adapters
  and the IDE plugin.
- Override the library's older Jackson dependencies together. Use a dedicated synchronous byte-stream factory and
  mapper, counting compressed and decompressed input before allocation. Enforce the profile's byte, depth, token,
  string, key, number and collection bounds before constructing generated model objects. Initial parser bounds are
  16,384 characters per string, 128 per key and 20 per number; decoded profile fields keep their tighter limits.
- Refuse duplicate keys, trailing tokens, unknown or forbidden fields, scalar/container coercion, numeric enum
  values, unknown enum fallbacks, lenient JSON and class-name polymorphism. Disable source excerpts in errors;
  report bounded rule/path identifiers without raw bodies. Validate against bundled schemas with no network or
  report-directed file access. Full-model field recognition does not replace the profile allow-list.
- Write fresh allowed model objects, validate the serialized result, and test absent/default/false values and literal
  braces. Do not round-trip an untrusted object graph or retain hidden extension fields.
- Import scanner reports separately: at most 16 MiB, 16 runs and 10,000 results, with an initial 5-second CPU and
  256 MiB memory budget outside the daemon's main execution path. Strip invocation commands and environments,
  artifact contents, snippets, attachments, graphs, code flows and external references. Convert absolute scanner
  paths only through a verified root mapping for the pinned repository; never execute a command from a report.
- Page normalized output within the live limits of [B291](README.md#b291), with `porelo/sourceKind: "scanner"` and
  daemon-controlled execution evidence. Failed, oversized, timed-out or incomplete imports remain visibly held,
  partial or failed; missing results and imported suppressions cannot clear findings.

## To be checked

- Inspect the released library's generated classes, annotations and extension handling, resolve the effective
  dependency graph and verify the maintained Jackson set against current advisories and binary compatibility.
  Do not infer a vulnerability or its fix from an advisory identifier in isolation.
- Verify which stream constraints the selected Jackson release exposes; implement missing limits explicitly.
  Measure allocation, CPU termination and memory enforcement with deep, wide, compressed and malformed fixtures.
- Choose the worker launch and output channel, test termination after every budget failure, and verify native-image
  serialization for CLI and daemon as well as the IDE plugin's dependency isolation.
