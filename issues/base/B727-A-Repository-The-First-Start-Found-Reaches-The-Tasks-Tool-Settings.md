# B727 — A Repository The First Start Found Reaches The Task's Tool Settings

```json
{
  "$schema": "issue-1.json",
  "id": "B727",
  "title": "A Repository The First Start Found Reaches The Task's Tool Settings",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 8,
  "blockedBy": [],
  "covers": "A tool repository the first start's draft names, and its `extensions.maven` block, once the person takes the draft, reach the adapter's Configure at the start in `default`, so the task holds the settings file with the broker's mirror, as the egress host already reaches its egress.",
  "openQuestions": 0
}
```

**Why this size.** One path from the taken draft to the start's tool settings, a stand-in adapter and a test through the first start. Estimate: 45 minutes (Core's median for S).

Found on snapshot.512 in the project `default`: the host of a private repository from the person's Maven settings reached the task's egress, but the task held no `.m2/settings.xml`. The adapter, measured: Detect gives `repository.inhouse`, and Configure answers the settings file with a broker mirror and no credential. Read at 177f3cae2: `ToolSettings.ask` returns no file when `project.toolRepositories()` is empty, so the draft's `tool_repositories` entries (`ProjectProposal`) do not reach the project the start uses in `default`, while the egress host does.

## Plan

- When the person takes the first start's draft, its `tool_repositories` entries become the started project's tool repositories, as its egress hosts become its egress.
- The start then asks the adapter's Configure for them as for a project that declares them, and writes the file it answers into the task.
- The draft's `extensions.maven` block reaches Configure at the start by the same path, as `mirrorOf` already does from a project file. Ecosystems' EC26 (High, built together with this one) reads `extensions.maven.ownSettings` there and then answers no file. No contract change.
- No credential enters the task: the file names the broker's mirror only.

## Acceptance

- Red first, in the JVM, through FirstStart and the start in `default`, with a stand-in adapter answering `repository.inhouse`: after the draft is taken, the task's files hold `.m2/settings.xml` from Configure.
- The file names the broker's address and no password.
- A draft whose `extensions.maven` block is taken hands that block to Configure: with `ownSettings` the stand-in answers no file, and none is written.
- A draft without `tool_repositories` starts as today.
