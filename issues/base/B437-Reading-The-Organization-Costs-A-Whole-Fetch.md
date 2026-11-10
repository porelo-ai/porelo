# B437 — Reading The Organization Costs A Whole Fetch

```json
{
  "$schema": "issue-1.json",
  "id": "B437",
  "title": "Reading The Organization Costs A Whole Fetch",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": [],
  "covers": "A question that only shows the organization or a project answers without waiting on a whole fetch of its repository, and a decision still fetches."
}
```

**Why this size.** a read answers from the kept state and refreshes after; every security check keeps reading fresh.

Implementing since 2026-11-10; in Review since 2026-11-10, until the operator's test. Built by Codex under Core's
supervision on 2026-11-10 (`0969e6dff`), taken onto main then. In `porelo-project` ST308; the operator asked for it
on 2026-11-10.

**What must be true.** A question that shows the organization or a project to a person answers without waiting on a whole
fetch of the organization's repository, and a decision that rests on the organization still fetches.

## Why

`porelo org --json` and `porelo project show` on a project this machine does not hold each take about 2 s, so a page's
content comes well after its head. On an empty account both take 0.03 s, so the program's own start is not the cost.

Read in the code: every read goes `GitTreeStore.read` to `TreeRepository.fetch`, a scratch clone of the whole repository in a
temporary directory each time, then a walk from the commit kept, and the forge's lists of keys and owners over http.
`project show` gets there through `ProjectTaking`. Nothing asks first whether the tip moved.

Measured from one machine, the pieces against github.com: an ssh handshake 1.0 s, `git ls-remote` over https 0.4 s, a small
bare clone over https 0.5 s, one `<login>.keys` page 0.2 s. The whole of it was not measured against a real organization: only
an operator's account holds one, and it reads with their agent and vault.

## To be checked

- How much of the 2 s is each piece on a real organization, with `GIT_TRACE2_PERF` or a timer around the fetch, the walk and
  the forge's calls.
- Whether answering from `GitTreeStore.last()` at once and refreshing after is the right shape. That answer has no history
  to check against, so no security check may rest on it, and it must say how old it is. A flag for a page that only shows,
  or a fetch only when `ls-remote` shows a new tip: the second still costs the handshake.
- Whether a person's `project show` should take a project at all, or only read what is placed.

## Decided

After the next test. A read of the organization answers from the kept state first and refreshes after. No security check rests
on the kept state: a start's and a host's checks go on reading fresh. The window marks a stale value until the refresh arrives,
such as a role just withdrawn.

## As built

### Measurement before implementation

The existing signed-history fixture created a founder and two person cards, three commits, then a local bare origin.
Six reads separately timed `TreeRepository.fetch`, a new `TreeWalk` from the beginning, and the fixture's identity-source
calls. The walk column excludes those calls. No network, real organization, account credential or GitHub request was used.

| Sample | Fetch | Walk excluding forge | Forge | Forge calls |
|---|---:|---:|---:|---:|
| First read | 104.482 ms | 549.897 ms | 1.644 ms | 4 |
| Median of five subsequent reads | 55.997 ms | 396.042 ms | 0.033 ms | 4 |

The subsequent fetch times were 34.547, 69.921, 48.343, 70.890 and 55.997 ms; the walk times were 396.042,
389.978, 471.367, 348.145 and 415.782 ms. Forge times were 3.335, 0.033, 0.037, 0.033 and 0.030 ms.
These are local fixture costs, not a breakdown of the observed two-second network read.

### Display and refresh

`org` and `project show` use a display-only path. The checked kept record answers without a fetch, forge lookup,
credential opening or project taking. `lastChecked()` is the timestamp-bearing form of `last()` and uses the same
record validation. Missing, malformed, unsafe or wrong-address records take the fresh path; none is served as kept data.
The check timestamp is part of the atomically replaced owner-only record. Legacy records have unknown age.

Kept answers carry `stale: true`, `checked_at` and `age_seconds`. Text says the timestamp and age too. A kept project
refusal carries the same metadata and also schedules refresh, so new projects and membership grants can become visible.
Local projects need no organization and have null check metadata. No kept signing key validates newly fetched project
content: an opened project's kept answer says its repositories cannot be checked yet. `--refresh` on either display
command returns a complete freshly checked replacement, including project-file details; incomplete results stay stale.
Only a complete display refresh replaces the kept snapshot; a partial walk cannot erase its prior successful check time.

A separate helper process refreshes after a kept answer. Its input is closed and its output is detached from the page's
pipes. Refreshes of the same check generation coalesce, including unchanged Git tips; failed attempts have a short retry
interval used only to limit work. Failures are recorded safely and never clear staleness. The helper has a two-minute
deadline and waits up to ten seconds for cancellation cleanup. The entire trust-record transaction is locked across
threads and processes, including checkout and host reads; kept reads never wait on that lock. A superseded refresh checks
the configured address inside that transaction and before persisting, so it cannot replace another organization's anchor.

`project show` already used `OrgAccess` in this checkout. It does not call `ProjectTaking`, install a project or read a task
workspace. Starts and `TakeProject` still take projects as before.

### Caller audit

These are all main-code callers of the store's `read`, `readCheckout` and `last` family, followed by every caller of the
shared `OrgAccess.read` funnel. Contract fixtures and tests deliberately exercise the fresh API and are not product callers.
The revision-specific `GitTreeStore.read(identities, revision)` overload has no main-code caller.

| Caller | Shows or decides | Result |
|---|---|---|
| `OrgAccess.show` → `lastChecked` | Shows organization/project details | New kept-first path; missing/invalid state uses a guarded fresh display read. |
| `OrgAccess.fetched(display=false)` → `read`; fallback → `lastChecked` | Shared; decisions also use it through `OrgAccess.read` | Still fetches first. Its old unreachable fallback remains explicitly stale. |
| `OrgAccess.fetched(display=true)` → guarded `refresh`; fallback → `lastChecked` | Shows a fresh replacement through `OrgAccess.refresh`, or answers a cache miss | Fetches and walks; only a complete result replaces the kept snapshot, and an organization switch supersedes it. |
| `IdentityOrganization.fetched` → `read` | Decides identity authority and feeds starts | Fresh. `required`, `requiredIfAny` and `requiredUnlessEmpty` retain fresh checks. |
| `IdentityOrganization.read` → `last` after an unreachable fresh read | Mixed inspection/start/revocation funnel | Still attempts fresh read first; stale result retains its flag. |
| `IdentityOrganization.requiredUnlessEmpty` → `last` after fresh `Empty` | Decides whether a first card can be created | Kept existence only refuses an apparent empty organization; never grants. |
| `OrgSetup.checked` → `read` | Decides which organization setup may accept | Fresh. |
| `ProjectSignRepositoryFileCommand.fromCheckout` → `readCheckout` | Decides signing authority | Walks the current checkout against the record; deliberately fetches nothing. |
| `GitTreeStore.last` → `lastChecked` | API implementation | Same validation, now with timestamp available to displays. |
| `OrgRefreshCommand.refresh` → `lastChecked`, `refresh` | Shows; decides only whether duplicate work is needed | Guarded fresh refresh; timestamp/retry checks never authorize an action. |
| `GitTreeOffline.check` → `fetchFor` | Decides host grants and withdrawals | Still fetches and validates under the host pin. |
| `OrgReading.display` | Shows organization/project details | Chooses kept display or guarded fresh replacement; decisions do not use it. |
| `OrgCommand.answer` | Shows organization | Changed to kept display; explicit `--refresh` fetches a replacement. |
| `ProjectShowCommand.answer` | Shows a project | Changed to kept display; explicit `--refresh` uses fresh organization and file checks. |
| `OrgCardCommand.answer` | Shows an exported person card | Existing fresh-first path retained outside the two detail commands. |
| `OrgHostCardCommand.answer` | Shows an exported host card | Existing fresh-first path retained. |
| `ProjectListCommand.answer` | Shows project ownership/source rows | Existing fresh-first path retained. |
| `OrgWriting.current` | Decides the base of organization changes | Fresh; stale base refused. All role, grant and withdrawal writes retain this ground. |
| `ProjectRepositoryCommand.place` | Decides project-file changes and signing keys | Fresh; stale authority refused. |
| `ProjectCreateRepositoryCommand.create` | Decides repository creation | Fresh; stale authority refused. |
| `ProjectAddressCommand.filedNow` | Decides address changes | Existing fresh read retained, beside the enclosing write's fresh ground. |
| `OrgHostCommand.heldCard` | Assembles a host-card change | Existing fresh read retained, beside the enclosing write's fresh ground. |
| `OrgHostRefreshCommand.hosts` | Decides where to hand a forge credential | Fresh; stale hosts refused. |

The identity funnel's callers are `IdentityShowCommand`, `IdentityKeysCommand`, `IdentityCreateCommand`,
`IdentityRevokeCommand` and `ProjectTaking` through `read`; `IdentityRotateCommand`, `IdentityBackupKeyCommand` and
`IdentityRenewCommand` through `required`; and identity creation through `requiredIfAny` or `requiredUnlessEmpty`.
They are unchanged, including the inspection calls, since the funnel also supplies decisions.

### What the window reads

The existing window's organization source executes `org --json` and `project show --json` directly. There is no daemon
method answering this organization view. `Projects` and `WatchProjects` render `ProjectInventory` from locally placed
files; neither fetches an organization, and neither signals an organization refresh. `TakeProject` is a decision and stays
fresh. No daemon method needed converting to a kept organization read.

The window reads the organization's JSON `stale` into `OrganizationView.stale`, which draws its stale notice; that notice
currently assumes an unreachable store, so its wording also needs to cover a pending background refresh. Its project
parser currently ignores `stale`, `checked_at` and `age_seconds`. Its organization controller reads on app/page opening,
explicit Refresh and after writes; it has no automatic organization-refresh subscription. Therefore updating this Core checkout alone does
not make the existing window receive and render the fresh replacement automatically. The Core replacement API is
`org --refresh --json` / `project show PROJECT --refresh --json`; the window must issue the second read after rendering the
first, preserve its request ordering, replace the value even when only forge information changed at the same Git tip, and
read the project's stale metadata too. That integration is the window's (porelo-frontend), and nothing of it is built
here.

### Review and verification

The read-only plan review and code review used a different configured model. The code review found cached refusals that
did not schedule refresh, a superseded refresh that could replace another organization's anchor, and cancellation that
returned before subprocess cleanup. Behavior tests reproduced each before its fix. The repository-named review skills
were unavailable in the installed catalogue; the reviews applied their stated concerns without claiming a versioned skill
review.

Unit and local repository/process integration ran offline on Java 25. A clean selected run passed 243 core, 111
organization, 15 host and 498 app tests, with environment-dependent and separately reproduced baseline failures excluded
by name. The final focused clean run passed all 29 new app tests, 58 existing reading-command tests, the rollback test
and both updated store invariants. The three slow organization classes were exercised under `-Pwhole`: 204 store,
108 write and 79 write-contract tests; the two directory expectations changed for the persistent lock were rerun green.
The documents profile passed 28 tests, and the shared, citation, README, documentation-site, action-pin and formatting
checks passed.

The requested selection without exclusions cannot pass here: two organization tests and 72 app tests failed socket creation or
SSH-agent setup. Four other app failures also reproduce with the original command sources: three ecosystem helper Detect
tests and the relative-repository-path verification test. The whole-unit attempt stopped in `wire` with 32 socket-directory
ancestor-ownership failures, one refused socket bind and three explicit skips. Those were Codex's sandbox, not
the code: taken onto main on 2026-11-10 (beside [B566](README.md#b566) and [B593](README.md#b593)), organization/git-tree, app-organization and apps/app
ran whole outside it, socket and SSH-agent tests included. See the last line below.
