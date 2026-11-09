# Writing a planning adapter

A planning adapter keeps stories and issues in its own store. Porelo discovers installed executables by name and
calls them through [the planning API](https://github.com/porelo-ai/porelo/tree/main/planning/api), independently of
the store's implementation and license. The API and its only Porelo dependency, `porelo-wire`, use Apache-2.0.

Import `org.fuin.porelo:porelo-bom` and depend on `org.fuin.porelo:porelo-planning-api`. Implement
`org.fuin.porelo.planning.api.PlanningAdapter`; the executable's entire main method is:

```java
public static void main(String[] args) {
    PlanningMain.run(new MyAdapter(), args);
}
```

Install the executable under its `name()`, matching `[a-z][a-z0-9-]{0,62}`, in
`$XDG_DATA_HOME/porelo/planning` (the XDG data directory's `porelo/planning`) or
`/usr/libexec/porelo/planning`. Discovery sorts executable regular files within each directory. The first directory
wins for a name, even if its description fails; each failure remains in the listing with the executable named.
Like ecosystem discovery, standalone `describe` starts with an empty environment. The running adapter inherits the caller's environment.
Descriptions have ten seconds and at most 64 KiB. A name must equal the executable's filename and
`protocolVersion` must be at least `PlanningProtocol.LOWEST`, currently 1. Newer versions are accepted.

`describe` prints the same JSON object as varlink `Describe`: `protocolVersion`, `name`, `version`,
`capabilities` and `credentials`. Capabilities are `write`, `watch`, `issues`; credential kinds are `github-app`
and `token`. The version is a string, including an empty string if the adapter has no version to report.
Describe requires no project, settings or token and works even without a workflow.

`serve <socket> <directory>` checks that the caller-made directory exists, binds an owner-only socket, calls
`initialize(directory)`, then prints `ready <socket>` and serves `org.fuin.porelo.Planning1`. The caller makes the durable project directory, intended as
`$XDG_STATE_HOME/porelo/planning/<project>`, where an adapter may keep its clone or cache. The client makes a private
socket directory within the caller's runtime directory and removes only that directory when closed. It starts the
executable with the caller's environment, as a build reader does, and restarts it after it has ended. Startup and
Describe have ten seconds; ordinary calls have 120 seconds. Watches have no overall deadline and use separate
connections, so writes and refreshes can run while a watch waits. Closing the client cancels its watches and reaps
the adapter process. The durable project directory survives.

Every data operation receives `settings` as an unread JSON object and `token` as a string. The caller supplies a
short-lived installation token for `github-app`, or a credential value for `token`. The adapter neither mints nor
stores credentials and must never print, log or persist them. It runs as the caller's account with that account's
environment; this contract does not sandbox it. Install only adapters trusted with that account's access.

The [published IDL](https://github.com/porelo-ai/porelo/blob/main/planning/api/src/main/resources/org/fuin/porelo/planning/api/org.fuin.porelo.Planning1.varlink)
defines every wire field. These record shapes complete the choices the API needs:

- `PlanState(name, kind)` uses `WAITING`, `WORKING`, `CLOSED` or `OTHER`. A newer unknown kind reads as `OTHER`
  in Java; it must never authorize starting work. Which states are working states is Porelo's alone: porelod
  takes them from `planning.work` in `project.yml` and reads an adapter's `WORKING` as a hint only. An adapter
  that cannot tell answers `CLOSED` for a closed state and `WAITING` for every other. The `Workflow.states` list
  is board order for both stories and issues. `PlanPhase(name, states)` groups state names; `Workflow.phases` preserves their order.
- `StorySummary(id, title, state, link, issues, priority)` and `Story(id, title, state, link, description, priority)` carry the
  adapter's stored state, which may be empty for a story with issues. Computing the board state belongs to porelod.
  Both carry `priority: ?string`, the story file's free text in the adapter's own words (for example, Highest,
  High, Medium or Low), absent when the story has none. Older replies may omit it; absent/null decodes to null.
  `StorySummary.issues: ?[]IssueSummary` carries all that story's issue summaries at the listing's one revision.
  An adapter with `issues` supplies it, including `[]` for an issue-less story. Missing or null means a snapshot
  is unavailable from that older adapter; it must not be interpreted as an issue-less story. The four-argument
  Java constructor remains available and represents that absence. Board readers require this snapshot (otherwise
  refuse with `NotSupported("board-snapshot")`), request all stories without a states filter, and compute and filter
  their states locally. They read `GetWorkflow` too and retry when the two revisions differ: two calls independent
  of the number of stories, with no mixture of revisions. Every revision covers workflow, stories and issues and
  is never reused after a change.
- `IssueSummary(id, repository, title, state, size, blockedBy, link)` uses the project's repository key, a string
  size and a list of issue ids. `Issue` adds `whatMustBeTrue`, a string of acceptance text, and
  `subAgents: ?[]SubAgent`, the proposed sub-agents.
- `SubAgent(name, kind, agent, model, brief)` names a proposal, its kind (`BUILDER`, `TESTER`, `READER` or `OTHER`),
  the proposed agent and model, and the brief's complete Markdown text. `brief` is never a host path; the adapter
  reads any brief file in its store and delivers its text. An issue without proposals answers `subAgents: []`;
  clients decode an absent or null list as `[]` for compatibility with older adapters. Proposals appear only on GetIssue's
  full `Issue`, with no new method or proposal summary field. A newer unknown proposal kind reads as `OTHER`
  and must not be started automatically. Reading rounds and the operator's comment belong to Tasks1.
- `StateChange(story: ?string, issue: ?string, to: string)` names exactly one story or issue and its requested
  state. Set exactly one identifier; omit the other or send null. Neither, both, blank identifiers and mistyped
  fields are invalid parameters. This follows the separate story/issue identifiers used by `Change`.
- `Change(revision, stories, issues)` invalidates those ids. Both lists empty means reread the whole board,
  including its workflow. Slow watchers may coalesce notifications into a whole-board invalidation.

`PlanningAdapter.Result<T>` captures a read value and its opaque string revision together. Reads do not fetch:

| Method | Reply and behavior |
| --- | --- |
| `GetWorkflow` | `workflow, revision`, the one ordered workflow |
| `ListStories(states)` | `stories, revision`, including each story's issue summaries; filters the **stored** state, absent means all, `[]` means none |
| `GetStory(story)` | `story, revision`, including its description |
| `ListStoryIssues(story)` | `issues, revision`, summaries; requires `issues` |
| `GetIssue(issue)` | `issue, revision`, including `whatMustBeTrue` and `subAgents` with complete briefs; requires `issues` |
| `SetStates(changes, revision)` | One new `revision` for all story and issue moves or none; requires `write`, plus `issues` when the batch contains an issue; refuses a story with issues |
| `WatchChanges` | `change`; requires `watch`, atomically subscribes and captures an initial revision, then changes with varlink `more`; without `more`, answers once |
| `Refresh` | `revision`; requires `watch`, fetches immediately and publishes any change found to existing watchers |

**Before the first release**, `SetStates` replaces and removes both `SetStoryState` and `SetIssueStates` from
Planning1 and the Java API (`setStates`). Adapter authors must replace both implementations with this one method;
there are no compatibility aliases. One board Save sends every pending story and issue move against one revision.
Validate the entire batch before writing; a refused move changes neither resource data nor the revision. Only
issue-less stories may move directly; `StoryHasIssues(story)` still refuses a story with issues.

Adapter methods can run concurrently. Each `Watch` is independent; `next(timeoutMillis)` waits at most that long,
returns null on timeout, and `close()` releases it. Disconnecting the socket closes the subscription. A successful
write publishes one revision. An empty batch still checks the revision, writes nothing and returns the current
revision without advancing it. Repeated identifiers within the same resource kind answer `StateNotAllowed(item, from, to, reason)` with
reason `Duplicate story` or `Duplicate issue`; a story and an issue may share an id. An empty batch requires only
`write`. A successful mixed batch emits one invalidation covering its stories, its issues and their owning stories.
Writes must detect concurrent store changes before committing, including changes not yet fetched into the read
cache: refresh and refuse the obsolete revision, or report a store conflict without overwriting remote work.

A watch's first reply is part of the invalidation protocol: **compare it with the revision of the preceding read**.
A different revision requires rereading the whole board; never discard the first reply as an unexamined baseline.
The initial revision and subscription must be captured atomically, and a subsequent write must remain pending even
if it happens before the server's first take. Later notifications may coalesce to a whole-board invalidation at
the latest revision. Closing a subscription unblocks a pending `next` with null, never a fabricated change.

Every data method answers `WorkflowMissing(detail)` if the project lacks its workflow, before capability checks.
Other named refusals are `StaleRevision(revision)` with the current revision, `NoSuchStory(story)`,
`NoSuchIssue(issue)`, `StateNotAllowed(item, from, to, reason)`, `StoryHasIssues(story)` and
`NotSupported(capability)`. A story with issues has no independently writable state; `StoryHasIssues` does not
invent a `from` state. StateNotAllowed always identifies the refused item and explains why. A refused batch changes nothing,
including when an unknown issue follows a valid one. Porelo treats revisions only as opaque strings to hand back.

Store failures follow Build2's shape: `CredentialRefused(detail, retryAfter)`, `RateLimited(detail, retryAfter)`,
`NoSuchStore(detail, retryAfter)` (absent or not visible, the counterpart of `NoSuchRepository`) and
`Unreachable(detail, retryAfter)`. `retryAfter` is nonnegative seconds, 0 when unknown. Bad adapter settings answer
`InvalidSettings(detail)`; another explained failure answers `Failed(message)`. Details must be safe text, never a
token or an unfiltered remote response. `InstalledPlanning` preserves named errors and their fields; an unknown
newer error remains a `PlanningException` with `errorName()` and `parameters()`, so diagnostics need not parse a
sentence. These structured fields keep their original values; render them as bounded, terminal-safe text when
showing them. Null unknown extension fields are accepted. IDs, repository keys, state targets and revisions must
be nonblank; description text, links, optional briefs and an unused stored story state may be empty.

Discovery bounds and removes control characters from version, capability and credential metadata. A blank or
control-only version is shown as absent. Startup output is discarded because an inherited credential could be
printed by an adapter; it is never included in a failure diagnostic. Ordinary calls serialize independently of
lifecycle operations, so a blocked call cannot prevent `describe()` or `close()`. Restarting or timing out an
adapter closes its watch connections; registration and closing are coordinated so no watch escapes cleanup.
