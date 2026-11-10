# B538 — A Task's Container Without The Doubled Project Name

```json
{
  "$schema": "issue-1.json",
  "id": "B538",
  "title": "A Task's Container Without The Doubled Project Name",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 4,
  "blockedBy": [],
  "covers": "A task's container is `porelo-pi`, not `porelo-porelo-pi`, with tasks made before kept working and no clash between projects. High priority."
}
```

**Why this size.** for the build, split below: shared identity lookup, creation, credentials and messaging, daemon access,
and the window's validation cross module and repository boundaries. This work is the plan only; the build is [B596](README.md#b596) to [B602](README.md#b602).

Implementing since 2026-11-10 (`porelo-project` ST307; plan first). Filed 2026-11-09 from move 3's findings 10/19 and [B532](README.md#b532), as the operator decided
it (the operator's decision of 2026-11-09 on the open points of wave 2, point 6: keep the name now, plan the change).

High priority (the operator, 2026-11-09).

**What must be true.** A task of repository `porelo-pi` in project `porelo` is the container `porelo-pi`, not
`porelo-porelo-pi`; tasks made before keep working (attach, resume, stop, their mail, vault entries, sockets), and no
two projects can make one container name.

## Plan

### Design to approve before building

The proposal changes names for **new tasks only**. An existing task keeps its actual container name, recorded id,
and every path and external account tied to them, running or stopped, until it is removed. Compatibility has no
release deadline: old and new task identities remain readable for the lifetime of their state and retained history.
There is no container rename, directory move, secret copy, socket alias, or rewrite of signed messages during upgrade.
Start by logical project/task must find an existing old task before considering creation under a new name.

**Proposed encoding.** Let `p` be the project and `t` its logical task name. Use `porelo-` + `t` for project `porelo`,
and `porelo-` + `p` + `_` + `t` for every other project. Thus repository `porelo-pi` yields logical task `pi` and
container `porelo-pi`; repository `porelo-pi-x` yields task `pi-x` and container `porelo-pi-x`; project `pi`, task `x`
yields `porelo-pi_x`. This is a project naming rule, independent of the selected agent.

**Short names (the operator, 2026-11-10).** Two optional fields shorten the encoded name: `project.short` in the
project file, and a task's short name given at its start (`--short` on `porelo task start`, a `short` field on
Tasks1 `Start`), recorded in the task's identity entry and never changed after creation. Both are lower case,
`[a-z][a-z0-9-]*`, at most 16 characters, no underscore and no `porelo-` prefix, so they stay valid in a container
name and the two forms stay disjoint. With them a container is `porelo-<project short>_<task short>`; each one
missing falls back to the full name (`porelo-<project>_<task>`, or `porelo-<project short>_<task>`). Project
`porelo` keeps its own form `porelo-<task short or task>`, which "What must be true" asks for. A short name is a
spelling, not an identity: the identity entry still records project and logical task, every lookup by logical
name keeps working, and the window shows `nickname` and the task's name as before. Two projects may choose the
same short name; the spelling then no longer proves uniqueness, so the reservation below refuses the second
start with `ContainerNameTaken` naming the owner, as for a legacy overlap. Changing `project.short` later changes
only names of tasks made after it; existing tasks keep their actual names. Project and task alphabets
contain no underscore, so the two forms are disjoint and the second has exactly one separator: equality implies
the same project and task. Do not truncate or hash the encoded name. Refuse an overlong name with the available
shorter task length. Reserve logical `login-<digits>` for **new** tasks in project `porelo`: the resulting name is
the login-container namespace (`runtime/src/main/java/org/fuin/porelo/runtime/ContainerName.java:263`). An explicit
start gets BAD_TASK_NAME and the action to choose another name; default selection uses `-work` before numbering.
An existing legacy task with that logical name remains usable under its original actual name. This prevents its
being hidden or swept by vault login (`apps/app-vault/src/main/java/org/fuin/porelo/app/AgentLogin.java:1137`).
The current rule is **already ambiguous**, contrary to the old cost note: project `web`, task
`app-x` and project `web-app`, task `x` both encode as `porelo-web-app-x`.

The evidence is `runtime/src/main/java/org/fuin/porelo/runtime/ContainerName.java:40`,
`core/src/main/java/org/fuin/porelo/core/project/Project.java:1184`, and
`apps/app-model/src/main/java/org/fuin/porelo/app/ProjectNames.java:10`. Default repository-to-task normalization
already strips leading `porelo-` at `apps/app-model/src/main/java/org/fuin/porelo/app/TaskNames.java:31`; the change
must preserve that logical name, the second-start choice, and its gate ref.

**One identity lookup, two formats.** Add a shared identity value `(format, project, task, container, recordedId)`
and lookup in `app-base`, usable without depending on an area. New tasks receive an owner-only durable identity
entry at `state/task-identities/<container>.json` (0700 parent, 0600 file), outside task mounts and normal task
record deletion, and an explicit logical-task podman label. Extend the existing
recorded-id check, not name-based adoption. Add the label constant beside the current labels in
`wire/src/main/java/org/fuin/porelo/wire/Sidecar.java:109`, read it in
`runtime/src/main/java/org/fuin/porelo/runtime/Podman.java:983`, and carry it in
`runtime/src/main/java/org/fuin/porelo/runtime/ContainerSummary.java:12`; absence means legacy, not an empty task.
For old tasks read the recorded id, project label/durable sidecar and
the old project-scoped suffix; never infer the project from the longest prefix or strip a supposed numeric run id.
Check project, actual name and recorded/live id for consistency. Missing or contradictory ownership is a typed
refusal for an action; a listing can still report incomplete/unrecognized state. Keep the pre-existing refusal
for tasks with no recorded id and for tasks predating restart support. `named` is a caption source, not identity.
No rewrite of old `task.json` or `sidecar.json` is needed. Area records and wire replies consume this lookup's
logical task and actual container separately. Path methods continue to take the actual container.

**Legacy overlap and concurrent starts.** The new encoding is injective among new tasks, but a new name can equal
another project's *old* name. For example, an old `pi/x` owns `porelo-pi-x`. Creating `porelo/pi-x` must answer a
new named refusal, proposed `ContainerNameTaken(container, project, task, ownerProject, ownerTask)`, with exit 64:
“container porelo-pi-x belongs to project pi, task x; choose another task name.” Unknown ownership is reported as
occupied/unrecognized, with absent optional owner fields, never guessed and never resumed. Do not offer removal
as the ordinary way out.

Before any helper, mailbox, policy, key or token is made, serialize resolution and reservation across CLI, daemon
and MCP using an account-wide durable file lock. Under it, inspect existing logical identities, all podman names
(including unrecognized containers), and every retained state namespace in the table below for the candidate name.
Check exact task and transport vault scopes under the vault lock too: an unreadable/locked vault that could hold
legacy scopes cannot prove a name free, so return the existing vault-needed refusal before creation. Unknown
ownership of leftover state or vault-only scopes reserves the name; do not borrow or overwrite them. Publish one
private reservation per actual name atomically in the identity directory, containing the logical owner, creation
attempt id, stage (reserved/created/retired), and holder pid plus start time. Release the global lock after
reservation, before the image build. A second start of that logical identity answers already starting/running or
resumes the recorded task; another owner cannot replace the reservation. A live matching holder means already
starting; elapsed age alone never makes it abandoned. Creation then records podman's id before
start, as today. Use no-replace publication and reject links; `FileLocks.holding` already checks lock inode identity
at `apps/app-base/src/main/java/org/fuin/porelo/app/FileLocks.java:53`.

A failed or killed creation leaves its reservation for an idempotent retry by the same owner. Reconcile it with
the recorded/live id; an unrecorded container is refused, not adopted or removed automatically. Finalize the entry
with an atomic replacement only for that attempt. Stop/remove and reservation cleanup use the same lock order
(name reservation before vault update); vault callbacks read committed identities without taking the name lock.
Keep active and reserved owners in both token-pruning censuses, including when podman has no container yet; retired
identity metadata preserves historical ownership without keeping retired secrets alive. Prune/clear are mutators
too: they must recheck claims under the name lock and cannot remove a live or recoverable attempt's files or keys.
Keep the logical-owner entry while retained history uses the name. For a legacy task, initialize that metadata
only on its first mutating operation, atomically from verified old records;
read-only operations need not write it. A failed metadata write leaves old records untouched and refuses the new
mutation; an interrupted temporary write is ignored and retried. No multi-directory or vault migration can fail
half-way because none is attempted. Never reclaim another logical owner's name from an absent container alone.

**Running through upgrade.** Upgrade CLI, daemon and hooks together; existing helper processes and containers keep
their actual names, arguments, tokens and mounts. The new daemon rediscovers old tasks, clearance watches and MCP
endpoints under those same names. Do not restart the task or replace its mounted socket inode to shorten a name.
After a normal stop, resume recreates helpers at the original paths and reads the original vault prefixes. An
already running task answers already up. Concurrent use of an older CLI to create tasks after this upgrade is
outside the proposed support contract; see the operator questions.

### State held by a task made before

Every row below stays under the task's **actual old name**, read and written there by the new code for as long as
that state exists. “Both formats” means resolving either kind of task, not probing both secret or socket paths for
one task. Only the identity/reservation metadata above is initialized once. Retained audit files keep their old
names after removal too; they are never merged into another logical task's history.

For compact citations below, `base/`, `model/`, `task/`, `messaging/` and `gate/` mean respectively
`apps/app-base/`, `apps/app-model/`, `apps/app-task/`, `apps/app-messaging/` and `apps/app-gate/`, each followed by
`src/main/java/org/fuin/porelo/app/`. `daemon/` means `daemon/src/main/java/org/fuin/porelo/daemon/`.

| State and measured owner | Compatibility action and retention |
|---|---|
| Container, workspace, environment, tmux session, annotations and mounts: `base/TaskRunner.java:398`, `base/TaskRunner.java:441`, `base/TaskRunner.java:467`; id in runtime and durable records: `base/TaskRunner.java:878`. | Keep name and id. Start/attach/stop/exec act on the recorded id, as `runtime/src/main/java/org/fuin/porelo/runtime/Podman.java:139` does now. No adoption by matching name or label. Workspace remains inside the original container. |
| Runtime `/run/user/<uid>/porelo/<container>/`: `base/TaskPaths.java:45`. Ruleset, resolver configuration, host address, sidecar: `base/TaskRunner.java:321`. | Keep paths and the sidecar's absolute paths. Hooks continue using the annotation; restore into these same paths after runtime loss, rather than creating a new-name sibling. |
| Durable `state/tasks/<container>/`: `base/TaskPaths.java:99`, complete copy list `model/TaskState.java:45`. | Keep recorded id, `task.json`, `base.json`, `credentials.json`, `parameters.json`, `grants.json`, `resume.json`, `unhanded-work`, `dns.conf`, `hand-in.limit`, `host-address`, `task-ca-certificates.pem`, `certifiers`, `certified-until`, `dnsmasq.servers`, `ruleset.nft`, `sidecar.json`, `granted`, `granted-addresses`. Existing save is atomic **per file**, not a transaction (`model/TaskState.java:144`); restore is `model/TaskState.java:193`. Add identity metadata without pretending this copies all state atomically. |
| Host-only completion `tasks/<container>/done`: `base/TaskPaths.java:104`, `base/TaskDone.java:31`. | Keep only in durable host state, outside task mounts. Never copy it from runtime or borrow another task's declaration. |
| Session `state/sessions/<container>`, mounted guide `state/guides/<container>`: `base/TaskPaths.java:85`, `base/TaskPaths.java:117`. | Keep session/conversation id and guide at original paths through stop/resume; normal removal deletes them. |
| Mailbox `state/mail/<container>`: `base/MessagingPaths.java:27`; complete layout `base/Mailbox.java:530`. | Keep box inbox/outbox/sent, host incoming/inbound/hold, filter/check queues, feedback/errors/index, per-transport active/deferred queues, sent/record/person files, signatures, receipts and attestations. Preserve message bytes, agent card, ownership and mount at `/run/porelo/mail`. Resolve logical addressees to the old mailbox, without creating a new one on read. Normal removal remains the deletion point (`task/TaskControl.java:530`). |
| Vault `task/<container>/{gate-token,provider-token,route/<destination>,signing-key,certificate,forge-token,mcp-key}`: `model/TaskSecrets.java:56`, `:88`, `:111`, `:323`. | Read/update/delete this exact original prefix only. Keep provider/route phantom tokens across resume. New names must not cause old owners to be pruned by `model/TaskSecrets.java:193`; all existing owners are counted under their actual names. Never fall back from a failed secret lookup to a similarly spelled task. |
| Transport secrets `transport/<scheme>/task/<container>`: `messaging/TransportLifecycle.java:404`, `:1944`. Addresses/displays maps `state/transport/<scheme>/{addresses,displays}/<project>.json`: `:417`, `:809`, `:815`; taken/members caches `:789`, `:799`. | Keep old room account, address and display entries keyed by old actual container, and keep their exact secret scopes. Enrollment/resume/retirement use that actual name. Change project clear's prefix-based secret deletion (`:933`) to exact verified owner membership, including removed-task identity entries; it must neither miss new names nor delete `web-app` when clearing `web`. |
| Broker `vault.sock`, `vault.token`, `routes/*.token`, broker/relay pid files and logs: `task/CredentialWiring.java:330`, `:113`; gate pid/log and signed ref: `task/GateWiring.java:145`, `:210`. | Preserve active processes and socket inode. Resume reconstructs them at old paths in the recorded helper order. Secrets stay out of saved helper environments (`model/TaskState.java:236`). |
| Clearance socket/watcher pid/log: `task/ClearanceWiring.java:71`; firewall reader/resolver/helper pid files: `base/TaskLifecycle.java:86`; screen and signing shim mounts: `base/TaskRunner.java:453`. | Keep live paths, process records and scopes. Census and reap the old task's own verified pids only. Do not move live sockets or reattach a mounted path to another task. |
| MCP gateway endpoint `runtime/mcp/<container>.sock`: `task/McpWiring.java:224`, `:235`; per-task daemon endpoint `<container>.daemon.sock`: `daemon/McpTaskSockets.java:222`; `mcp.json`, `mcp.out`, `mcp.log`, `mcp.pid`: `task/McpWiring.java:39`, `:57`. | Keep both old socket names, the vault MCP key, parent container names and child ownership. Reopen daemon endpoints for running helpers (`task/TaskControl.java:1115`, `daemon/PoreloDaemon.java:246`); a new daemon must not reopen a sibling's endpoint. |
| Logs/events/runtime agent configuration and helper arguments: `model/TaskInventory.java:1737`, `base/TaskHelpers.java:48`; log archives: `daemon/TaskMethods.java:423`. | Keep logs and all other files under the original runtime directory, including agent session/config files already owned by the host. Keep archive identities. Nothing reads the container workspace from the host. |
| Retained usage, clearance, hand-in and build records: `base/TaskPaths.java:26`, `:133`, `model/HandIns.java:703`, `model/TaskBuilds.java:520`; hand-in staging `model/HandIns.java:711`. | Keep `usage/<container>.jsonl`, `clearance/<container>.jsonl`, `hand-ins/<container>.jsonl`, `builds/<container>.jsonl`, and `runtime/hand-in/<container>`. Preserve clearance archival on removal and normal staging cleanup. Resolve review/history reads to the right actual identity. |
| Shared event and stop journals containing actual container names: `base/EventLog.java:162`, `base/PoreloPaths.java:277`, `:287`. | Keep historical task fields byte-for-byte; append new events with each task's actual name. Never rename old events inside the chained history to match a new spelling. |
| Task certificate history by fingerprint: `messaging/TaskCertificateRecords.java:48`, `:129`; incoming refs and host push/start records by logical task: `task/GateWiring.java:165`, `gate/HostPush.java:127`, `base/GatePaths.java:16`. | No rename: these are logical-task/fingerprint keys. Keep certificate principal `task:<project>:<task>` (`task/TaskCertification.java:150`), gate ref, starting branch and recorded forge branch unchanged. |
| Project/class/repository/commit podman labels: `base/TaskRunner.java:408`; inventory filters: `runtime/src/main/java/org/fuin/porelo/runtime/Podman.java:762`, `:969`. | Keep old labels; add the logical-task label to new containers only. `porelo-` and login exclusion still work. Verify id before treating labels as identity. Do not replace filters with the new project's textual prefix. |
| Other deletion paths: `apps/app-project/src/main/java/org/fuin/porelo/app/Prune.java:189`, `:308`, `:383`, `:595`, `:634`; token census `task/TaskLaunch.java:3974`. | Make orphan detection and both task/transport vault pruning reservation-aware, including starts with no container. The current 15-minute filesystem grace at `Prune.java:53` is insufficient; vault pruning has no such grace. Clear/prune retain historical identity entries and do not reclaim by age. Recheck under the agreed lock order before deletion. |

**Socket length and accepted alphabet.** Validate the encoded name and every actual Unix socket path before
creation. The current 64-character limit counts `/ssh-agent.sock` only
(`runtime/src/main/java/org/fuin/porelo/runtime/ContainerName.java:45`). The measured MCP daemon path formula is
`10 + uidDigits + 8 + 4 + containerLength + 12`: with a ten-digit uid a 64-character name takes 108 bytes, over
107. Use the minimum budget of the enabled paths and shorten only the *proposed logical task* in default-name
selection; explicit overlong names are refused, never silently changed. Existing valid tasks are not revalidated
against the new creation budget on attach/resume. Update the container alphabet in `daemon/McpTaskSockets.java:59`
and `daemon/TaskMethods.java:54` to accept the encoded underscore, while logical task names stay hyphen-only.
The full-container validator in `messaging/TalkPassCommand.java:222` must accept it too; it currently applies the
logical-task alphabet after stripping `porelo-`.
`task/TaskUsage.java:15` already accepts underscores. Test actual path bytes, not just character count.

### Every reverse lookup or name parser to change

All references below were found from `base/TaskRunner.java:116` and searches for `ContainerName` calls, literal
`porelo-` concatenation, prefix filters, substring/split operations and container-keyed paths in main sources.
Use the shared identity lookup in these places; do not scatter a second dual-format decoder across them.

| Current place (file:line) | Required change |
|---|---|
| `runtime/src/main/java/org/fuin/porelo/runtime/ContainerName.java:206`, `:223` | Separate pure new-name encoding from decoding a known format. Legacy suffix parsing requires the recorded project; a new-looking spelling alone cannot establish ownership. Keep login distinction at `:263`. |
| `model/TaskInventory.java:201`, `:930` | Populate `shortName`, `task`, `short` and display from logical identity, and keep `name` as actual container. Keep waiting-at-gate's recorded-branch test (`:1252`). |
| `model/TaskTarget.java:74`, `:102`; `model/TaskNames.java:40`; `task/TaskRefresh.java:114` (called directly by `daemon/TaskMethods.java:162`) | Replace synthetic old names with project/task matching over verified identities. Resolve exact actual names first, scoped logical names second; refuse ambiguity. Default-name occupancy includes both formats and in-progress claims. Refresh through the daemon must resolve the same old/new logical identity as the CLI. |
| `task/TaskRunCommand.java:452`, `:598`, `:674`, `:700`, `:703`, `:834`, `:1149`, `:1179`, `:1203`, `:1409` | Remote-name validation, start outside a checkout, second-start selection, already-running offers and bulk restart use identity fields, never cut prefixes. Preserve the existing choices rather than implementing default naming again. |
| `task/TaskLaunch.java:1160`, `:1245`, `:2210`, `:4546`; `task/StartCheck.java:838`, `:1524` | Resolve existing logical tasks before encoding a new name; carry the selected actual name through credential checks, launch, branch ownership and cleanup. These are all four direct task-area callers of `TaskRunner.containerName`, counting `TaskRunCommand.java:1409`. |
| `task/RunningClearance.java:235` | Remove last-hyphen truncation; it currently treats a suffix as a run id. Use the full recorded logical task for watcher replacement. |
| `task/TaskControl.java:328`, `:522`; `task/TaskCertifyCommand.java:75`, `:130`, `:158` | Gate cleanup, host token ending and certificate renewal use the verified logical name; all task paths and secret prefixes use actual name. |
| `model/ProjectNames.java:28`; `messaging/TaskConversations.java:253` | Project comes from verified labels/durable identity; eliminate longest-prefix guesses. Retained legacy records without proof remain unknown instead of becoming another project's mail. |
| `messaging/TaskRecipients.java:19`; `messaging/PersonsWords.java:165`; `messaging/TransportConversations.java:162`, `:944` | Direct addressing, person mentions, acting-account aliases and room addresses join logical task to actual container, preserving recorded displays and people routing. |
| `messaging/TransportLifecycle.java:496`, `:561`, `:933` | Room display derivation uses logical task; project clear uses exact identity ownership. Its `:577` split is capitalization of the *logical* name and may stay after decoding. |
| `messaging/MessageSigners.java:214`; `messaging/MessageWatch.java:1171`; `messaging/TalkPassCommand.java:67`, `:75` | Default-project signing principal, sibling peer construction, explicit project/task passthrough and ownership check use identity, with no prefix-based trust. |
| `daemon/McpTaskSockets.java:429`, `:434`, `:442`, `:707` | Child-name allocation and already-starting accounting use the same reservation/lookup as other starts. Filter unrecognized replies by proven project ownership or omit them on a task socket; textual resemblance grants no visibility. Keep caller/parent checks bound to the actual endpoint. |
| `task/TaskLogsCommand.java:311`, `:369`; `daemon/TaskMethods.java:428` | These cut only the constant `porelo-` for archive filenames; the prefix is retained, so this is safe. They do not recover project/task and must not grow such a role. |
| `model/TaskSecrets.java:540`; `apps/app-project/src/main/java/org/fuin/porelo/app/VaultClearing.java:369` | These split the vault namespace at the first slash to find its actual container owner. Keep that operation; do not decode project/task or strip either name format from secrets. |

Reverse *construction* also has to change, even where no string is split:

| Current place (file:line) | Required change |
|---|---|
| `messaging/PersonNote.java:100` | Rejected-work feedback finds the old mailbox through identity, not a newly encoded candidate. This is the remaining direct `TaskRunner.containerName` caller outside the task area. |
| `base/ReviewText.java:60`, `:77`; `model/HandIns.java:504`, `:507` | Resolve retained task history for a pending/rescued push; inject the shared base lookup without adding an area dependency to `app-base`. Never silently drop old instructions or hand-ins. |
| `model/TaskBase.java:208`; `model/ApprovalBranch.java:142`, `:145`; `gate/CheckoutApproval.java:241` | Review base and branch selection read the existing actual task's runtime/durable record. |
| `model/GateSupport.java:248`, `:354`; `gate/GateServeCommand.java:133`, `:288` | Forge-token reads, repository selection and gate signing resolve the old actual identity; a missing/mismatched identity must not select a new task's key. |
| `task/WorkspaceSetup.java:190` | Suggested names for waiting work test logical occupancy in both formats, including reservations, rather than only newly encoded names. |

### Host and window contracts

The host's task key is the **logical task**, not the container: `task/TaskLaunch.java:2985` calls
`host.start(request.task(), ...)`; the certificate principal is made at `task/TaskCertification.java:150`.
`host/src/main/java/org/fuin/porelo/host/HostTasks.java:104`, `:129` records
`/var/lib/porelo-host/tasks/<login>/<task>.json`; token name `porelo-task-<task>` is at `:93`, and a record's task/login
is checked at `:247`. Keep all of these. The installed predecessor has the same keys and retained certificate
records (`porelo-host`, `roles/porelo/files/porelo-host-person:3120`, `:3197`, `:3220`, read from `origin/main`).
Do not migrate or request another host certificate just to change container naming. Host end still receives the
original logical name after resolving the actual old container.

The window already receives the separation: `wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/15-task.varlink:105`
defines actual `name`, with logical `task`/`short`, `named` and `display` at `:223`.
`model/TaskInventory.java:883` produces these. Preserve those meanings in List, Watch and Start replies; add only
the named collision refusal and its documented fields in the task contract, implemented in `TaskMethods`.
No breaking rename or field repurposing in Tasks1.

Read-only measurements of the window's `origin/main`: `porelo-frontend`, `lib/src/client/models.dart:344`, `:623`
reads actual `name` and supplied `short`/`display`; `lib/src/app/task_certifying.dart:126` checks the logical
certificate principal. Those uses keep working. But `lib/src/app/task_names.dart:35` constructs the old container
to reject lengths (its limit at `:12` is 65, whereas the current core is 64); the new compact form could otherwise
be rejected before reaching the daemon. A receiving builder must remove that stale length calculation in favor
of authoritative daemon validation, or consume a versioned naming budget supplied by the daemon. Recommend
daemon validation, retaining local alphabet checks. Update mock identities at `lib/src/mock/machine.dart:2198`
and `:2210`; the tab fallback at `lib/src/ui/running_view.dart:591` strips only `porelo-` and is not an ownership
parser. Carry a consumer test for the new typed refusal and a mixed old/new List/Watch fixture; do not test wording.

The room transport's task option already accepts underscores (`porelo-message-matrix`,
`src/main/java/org/fuin/porelo/message/matrix/Lifecycle.java:46`, `:1949`, `origin/main`). The MCP helper passes the
task argument through without splitting it (`porelo-mcp`, `server/src/main/java/org/fuin/porelo/mcp/Main.java:83`,
`:90`, `:361`, `origin/main`). No change to those interfaces is required by this proposed encoding.

### Ordered build slices and tests that go red first

Sizes use the repository's XS/S/M/L definitions (`AGENTS.md:593`), not measured build durations. Each slice can
land before the next; keep emitting old names until slice 5 enables new creation. App tests stay in `apps/app`.
All unit tests use a temporary test context and fake command runner, with no real account, podman or paid agent.

| Order / size | Builder's part | Red-first test, level and harm prevented |
|---|---|---|
| 1 / M | Shared identity, legacy reader and pure injective codec: `app-base`/`app-model`, `ContainerName`, `TaskTarget`, `TaskNames`, `TaskInventory`, `TaskRefresh`; keep old creation active. | **Unit:** extend `runtime/.../ContainerNameTest.java` and app `TaskTargetTest`, `ProjectNamesTest`, `TaskStateTest`, `TaskRefreshTest`. Show wrong-task resolution for old/new forms, `web/app-x` versus `web-app/x`, wrong project/id, exact-name precedence, ambiguous and missing records, and hyphenated names including numeric suffixes. Exercise maximum uid/socket budgets, logical-name refresh through the daemon and default-name occupancy. Reserve new `porelo/login-123`, retain the legacy task, and prove inventory/login cleanup cannot hide or remove a task. |
| 1b / S | Short names: `project.short` in the project file reader and its check (core `Project`, `ProjectFileCheck`, doc/project-file.md), `--short` on the start and `short` on Tasks1 `Start`/`CanStart`, the identity entry records both; the codec takes them. | **Unit:** `ProjectReaderTest` refuses an upper-case, underscored, overlong or `porelo-` short name naming the key; the codec gives `porelo-ws_x` for short `ws` and task `x`, falls back per missing field, and keeps project `porelo`'s form; a second project with the same short name is refused by name at the reservation (with slice 2); a task keeps its name after `project.short` changes. **Contract unit:** `InterfaceDescriptionTest` holds the new field. |
| 2 / M — trust boundary | Atomic name reservations and crash recovery in base/task start/remove/prune; all start entry points share it. Add `ContainerNameTaken` and wire registration, without switching creation yet. | **Unit:** barrier-controlled concurrent starts through CLI/daemon-shaped callers produce one reservation, one container and no overwritten policy/mail; a different owner with a legacy-name overlap is refused before side effects. Inject failure before/after claim publication and after podman create/id recording; retry only the same attempt/owner, and refuse unrecorded containers, links, read errors and conflicting ids. `PruneTest` holds a live or recoverable attempt with no container beyond the current grace period: state/mail/keys survive, including immediate vault pruning. Separate JVM lock **integration** test in a temporary directory proves cross-process exclusion without podman. |
| 3 / M — trust boundary | Route all gate, vault and filesystem consumers through actual identity: `TaskSecrets`, `GateSupport`, `GateServeCommand`, `ReviewText`, `HandIns`, `ApprovalBranch`, `TaskBase`, `TaskControl`, certification and resume. | **Unit:** `TaskSigningSecretsTest`, `TaskStateTest`, `TaskRunCommandTest`, `ReviewTextTest`, `ApprovalBranchTest`, `HandInsTest`: an old task's gate signs/pushes with its original test key/token; a second task has distinct sentinel secrets; resume restores old paths and route tokens, pruning keeps both owners and pending reservations, removal never deletes the sibling. Locked/corrupt vault, vault-only leftovers and contradictory identity fail closed. Assert the resulting signed/refused operation and files, never log a secret. |
| 4 / M — trust boundary | Messaging and daemon/MCP consumers from the tables: exact mail/transport ownership, clear, watches, socket validation, parent/child and completion checks. | **Unit:** app messaging tests plus `daemon/.../McpTaskSocketsTest.java` and task-method tests: old/new tasks in different projects receive only their addressed mail; clearing one cannot erase another's secrets; `talk pass porelo-web_x` with no project reaches the intended mailbox; old parent can resume only its own child; underscore endpoint opens; wrong caller cannot say Done/stop/read the sibling. **Integration:** owner-only Unix sockets in the test context deliver to the intended endpoint after daemon recreation, while an existing mounted endpoint is left alone. No container runtime at this level. |
| 5 / S | Enable the new-name allocator for new tasks only; CLI, dry run, CanStart and daemon share the same result. Adapt acceptance helpers that synthesize old names (`acceptance/kit/src/main/java/org/fuin/porelo/acceptance/ReadySteps.java:190`, `SignInStartSteps.java:138`), examples and contract comments (`15-task.varlink:214`, `:819`). | **Unit:** a repository `porelo-pi` in project `porelo` creates `porelo-pi`; another start resolves an existing `porelo-porelo-pi` without creating anything; a task of project `pi` gets `porelo-pi_x`. Explicit-name and default-name paths agree; preflight/dry run cannot reserve or mutate. Preserve the existing unnamed second-start behavior. |
| 6 / S | Window handover: retain opaque actual names, replace stale client length rejection and teach the typed collision refusal. Changes belong to its receiving builder; this worktree supplies the contract and fixtures. | Consumer **unit:** a new-name task and a legacy task survive List/Watch decoding; Stop/Tail carry actual names, Start/certification carry the logical name; a core-valid compact name is not blocked locally. No UI wording/screenshot test. Core **contract unit:** `InterfaceDescriptionTest` and task-method tests hold the added refusal to `15-task.varlink`. |
| 7 / M | One upgrade acceptance scenario and operational documentation; no automatic renaming command. | **Acceptance, real machine:** create a kept task with `--agent stub` using a pinned pre-change package, keep a workspace sentinel, session, queued mail, credential route and an allowed MCP child. Upgrade the scenario account's CLI/daemon/hooks to the candidate while the task runs. Assert same container id/name, live gate, attach and mail; restart only the scenario daemon and prove old watches/MCP ownership return; stop and start/resume the old task and prove sentinel/session/mail/credential route still work. Start a new task and observe the new name; remove only the scenario's tasks. Use one SSH connection, an isolated account/test vault and a local fake provider; install the endpoint/transport prerequisites explicitly, without a conditional skip. Record the baseline package digest; merely renaming a candidate-created container is not an upgrade fixture. |

The required other-model plan reading (`AGENTS.md:874`) is complete. Its four findings are included above: the
login namespace, prune during reservation, passthrough's container validator and refresh's reverse construction.
Before implementation handover, run a clean unit
build with `-Pwhole`, then deploy the candidate and run the acceptance suite with `-am`; selected features also
name `-Dcucumber.glue=org.fuin.porelo.acceptance`. The added scenario restarts its own daemon, not the machine;
do not silently exclude it under `not @slow` because the existing daemon-lifetime feature is tagged slow
(`acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/task/task-outlives-daemon.feature:1`). No build or
machine test is part of this plan-only run.

## Acceptance

- A project with `short: ws` and a task started with `--short x` is container `porelo-ws_x`; without them
  `porelo-<project>_<task>`. A short name another project already uses is refused by name, never shared.
- A new task of repository `porelo-pi` in project `porelo` is container `porelo-pi`. Different logical project/task
  pairs cannot produce the same new encoded name; the named examples and generated alphabet cases prove it.
- An old-name collision or unrecognized occupied name gives a typed refusal before any candidate state or secret
  is written, including concurrent CLI/daemon/MCP starts. It identifies the known owner and offers another task
  name. No name/id mismatch can cause a different task to be resumed, attached, stopped, signed for or deleted.
- Tasks made before remain addressable by actual name and by their project-scoped logical name. Their container
  id, workspace, session, branch, mail, route tokens, signing key/certificate, room account and socket paths remain
  theirs through upgrade, daemon restart, stop and resume. Lost runtime state is restored to the original paths in
  unit tests. A running task is neither renamed nor restarted for this change.
- The single real upgrade scenario above proves the behavior against a pre-change task. Failure injection below
  it proves retry safety and isolation. All retained histories remain readable under the correct old identity.
- The window consumes mixed old/new tasks using the existing actual-name/logical-task wire meanings, accepts a
  core-valid short name and renders the collision refusal. No Tasks1 field changes meaning.

## Open questions

1. **Names outside project `porelo`:** answered by the operator on 2026-11-10: `porelo-<project>_<task>`, with
   the optional short names above (`porelo-<project short>_<task short>`).
2. **Existing container names:** answered by the operator on 2026-11-10: they keep their names for their lifetime.
3. **Mixed installed writers:** answered by the operator on 2026-11-10: the CLI, the daemon and the hooks upgrade
   together; old task helpers keep running.

## Built as

The operator approved the plan on 2026-11-10 and had it split: [B596](README.md#b596), [B597](README.md#b597), [B598](README.md#b598), [B599](README.md#b599), [B600](README.md#b600), [B601](README.md#b601), [B602](README.md#b602) (the
slices 1, 1b, 2, 3, 4, 5 and 7, in that order). Slice 6, the window's part, is porelo-frontend's.
