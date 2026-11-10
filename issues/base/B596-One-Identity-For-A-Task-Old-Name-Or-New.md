# [B596](README.md#b596) — One Identity For A Task, Old Name Or New

**Status:** Review. Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).
**Priority:** high (the operator, 2026-11-09, for [B538](README.md#b538)).
**Size:** M - the shared identity value and lookup, the legacy reader and the injective codec, with old creation kept.
**Blocked by:** -.

**What must be true.** Every command that names a task finds it through one identity - project, logical task, actual container and recorded id - whether its container was named the old way (`porelo-<project>-<task>`) or the new way (`porelo-<task>` in project `porelo`, `porelo-<project>_<task>` elsewhere). A name is never parsed back into a project by its longest prefix; `web/app-x` and `web-app/x` stay two tasks. New tasks are still created under old names until a later issue switches creation.

## Plan

[B538](README.md#b538)'s plan, slice 1 ("One identity lookup, two formats" and "Proposed encoding"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- A task is found by its identity, old and new forms alike, and no lookup confuses two projects' tasks; creation is unchanged.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.

## As built

The shared `TaskIdentity` and `TaskIdentities` in `app-base` separate format, project, logical task,
actual container and recorded id. The lookup checks runtime and durable id copies, live id, project
labels and sidecars, and returns named missing, ambiguous or contradictory-ownership refusals. Legacy
suffixes use the recorded project and retain every numeric suffix. No lookup writes or adopts state.
The logical-task label is read by the runtime; its absence selects the legacy reader. The separate
codec uses one underscore outside project `porelo`, refuses overlong explicit names with the task
budget for the uid and longest socket, and leaves legacy creation active.

Target selection, default-name occupancy, project ownership, inventory and daemon-callable refresh
consume the lookup. New `porelo/login-<digits>` names are reserved; default names add `-work`, and a
verified legacy task remains usable. Durable identity publication and reservations belong to the
later slice; this change creates neither. Other reverse-lookup consumers remain for the later slices.

Unit cases are added at the planned levels. The initial run could not resolve offline dependencies;
after the supervisor installed the reactor, the five slice classes passed all 49 tests. The legacy
module rerun exposed abbreviated fixtures without the ids and sidecars a real start writes. Those
fixtures now carry the recorded id and preserve the sidecar's original runtime paths, including in
the durable copy. Wholly unknown names keep the existing command answers without a runtime
action: stop and remove report nothing to do, and a foreign name gets its existing refusal.
Matching names with incomplete or contradictory ownership remain typed action refusals.

A listing keeps the legacy project-scoped task, short name and display for a row with a project
label but no recorded id. The label selects exactly one prefix and the whole suffix, including
digits; contradictory records do not get this fallback. This read-only caption never authorizes
Stop, Remove, Attach or bringing a task back: those still require the recorded id.

An exact actual name absent from the runtime inventory now resolves from its retained id and
sidecar, so shutdown still reaches the recorded id after a container rename. A present row with
contradictory ownership still refuses without falling back to history. The six other legacy module
classes passed all 170 tests, as did the task-run class's six original checkout-selection regressions.
The supervisor's app module rerun passed all 4197 tests. Its remaining daemon listing regression is
corrected in inventory; the local selected daemon rerun is blocked by socket-directory ownership
checks in this sandbox. The normal-environment daemon rerun and machine checks remain with the
supervisor. The linked worktree's real Git index remains untouched; document checks use a temporary
index and object directory under `target/`.

The required read-only code review identified retained-log and absent-status regressions and a generic
name check that rejected existing remote legacy login-named tasks. Read-only resolution now leaves
actual history keys to the readers' existing checks, while action resolution remains strict. The
login reservation now lives only in new-task validation and the encoded-name codec. A test-source
enum typo was also corrected. These corrections are covered by the successful selected unit run.
Document checks include new files through a temporary Git index and object directory under `target/`;
the linked worktree's real index remains untouched.
