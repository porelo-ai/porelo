# B140 — A Secret Scan Of The Workspace At Start

```json
{
  "$schema": "issue-1.json",
  "id": "B140",
  "title": "A Secret Scan Of The Workspace At Start",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 8,
  "blockedBy": [],
  "covers": "A host-side `git grep` of the checked-out tree for known credential forms at start, warn or refuse per project, false findings acknowledged in a host-side list the agent cannot write.",
  "openQuestions": 0
}
```

**Why this size.** M: a scan in the gate, a call in the start, a project setting, an acknowledgement file with its
command, and docs; several modules (`wire`, `gate`, `core`, `apps/app-task`, `apps/app-project`) and no contract
another repository reads. Estimate: 63 minutes (Core's median for M), p80 229. The analysis below was read again in
today's code and holds; only the detector and the point of the call were settled.

**What must be true.** When a task starts, its workspace is scanned for credentials, and a found one is named to the person before the agent works.

## Why

A token committed in the repository is in the agent's hands: it can push past the gate with it and it reaches the provider with the text (`how-it-works.md`, `faq.md`).

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §5; AISVS AC.12.2; the guidance to keep secrets away from AI tools. From a review of security guidelines, 2026-10-09.

## Plan

The scan on the host over the tree the task checks out, not its history; the finding said with file and line, the
value never shown; whether a finding stops the start or only warns, per project; a false finding acknowledged on the
host, where the agent cannot write. Read in the code on 2026-10-11: the task clones its workspace from the gate's
bare mirror (`TaskWorkspace.cloneScript`, seeded in `WorkspaceSetup`), so what it gets is a commit the host already
has; `TaskLaunch` runs its checks (`StartCheck.unknownHost`, `StartCheck.undeclared`) before the container is made.
Nothing scans the tree today; `CredentialScan` in `supervisor/` watches provider answers, not the workspace.

1. **The forms, shared:** the credential shapes `CommandMask` (`apps/app-task`) already keeps for masking command
   lines - forge, provider, cloud, registry and Porelo tokens, and the private key header - move into a public
   `CredentialShapes` in `wire`, each with a kind's name. `CommandMask` reads them from there, so masking and the scan
   cannot drift apart. No library is added: the list is short, and it is the one Porelo already trusts.
2. **The scan, in the gate:** `SecretScan` in `gate/src/main/java/org/fuin/porelo/gate/` runs `git grep -z -I -n -E`
   over the tree of the commit the task will check out, in the bare mirror, with those shapes (each is plain POSIX
   ERE; the private key matches on its header line only). For each hit it reads the path and the line number from
   the NUL-separated prefix, names the kind by matching the line's rest against the shapes in Java, and drops the
   rest there. It answers `Finding(path, line, kind)`; the matched text is never kept, printed or journalled. The
   history the task receives is not scanned: a credential removed in an earlier commit is not found, and the docs
   say so.
3. **At the start:** `TaskLaunch`, right after `TaskBase.select` has chosen the starting commit in the gate's mirror
   and before the container is made, calls it and says each finding as `secret    <path>:<line>  looks like <kind>;
   the agent can read it`. A task that reads every repository of its project has each of them scanned too. The
   project's `secrets: warn | refuse` (in `core/.../project/Project.java`, `warn` by default) decides whether the
   start goes on; `refuse` ends it with exit 65, as a start refused for want of a credential does. A resume does not
   scan again: the tree is already in the agent's hands.
4. **A false finding:** acknowledged in a list kept on the host beside the project, never in the repository, so the
   agent cannot add to it: `ProjectPaths.secretsKnown(project)`, `<state>/secrets-known/<project>.json`, owner-only,
   as `MessagingPaths.moderation` keeps a person's decisions about a project's peers. Each entry is `path:line` with
   a hash of the matched text, so it holds only while that text stays. `porelo project secret-known -p <project>
   <path>:<line>` adds one, and `porelo task start` names that command for each finding. Nothing in the repository
   acknowledges a finding.
5. **Docs:** `doc/how-it-works.md` and `doc/faq.md` (the token in the work tree, and that the history is not
   scanned), `doc/project-file.md` (`secrets:`), `doc/commands.md` (`project secret-known`).

## Built

- `wire/.../CredentialShapes`: the shapes `CommandMask` kept, each with a kind (forge token, provider key, cloud key,
  chat token, registry token, Porelo token, web token) and a private key's header, as plain POSIX ERE that Java reads
  alike. `CommandMask` takes its token expression from there; `CommandMaskTest` (29) and `ToolCallsTest` (13) green.
  Kinds are generic, so no provider's name is said.
- `gate/.../SecretScan`: `git grep -z -n -o -I -E` over the commit's tree in the bare mirror, one `-e` per shape. It
  keeps `Finding(path, line, kind, digest)`, one per place, the digest a SHA-256 of the file's blob id and the match;
  the match is dropped where it is read. An empty mirror has no finding; a git that fails, times out or answers more
  than the runner keeps throws, and is no clean tree. The plan's "hash of the matched text" became the file and the
  match: a private key's match is only its header line, the same in every key.
- At the start (`TaskLaunch`): right after `TaskBase.select`, `SecretCheck` scans `refs/heads/<base>` of the task's
  mirror and says each finding not acknowledged as `secret         <path>:<line>  looks like a <kind>; the agent can
  read it`, followed by the `porelo project secret-known` command for it. Under `refuse` the start ends with 65 before
  anything exists. A task with `--all-repositories` has each repository scanned after it is read
  (`WorkspaceSetup.readAll`); a refusal there is cleaned up as every refused start, with 65. A scan that cannot run is
  said, and ends the start only under `refuse`.
- `project.secrets: warn | refuse` (`Project.secrets`, `ProjectInfo`, the binder, writer, schema and file check).
  `warn` by default. A repository's own `porelo.yml` may not say it (`RepositorySettings.theProjects`): the repository
  is what the agent pushes to.
- `SecretsKnown` (`$XDG_STATE_HOME/porelo/secrets-known/<project>.json`, `ProjectPaths.secretsKnown`, written
  owner-only through a staged file): per repository, a place and its line's digest. `porelo project secret-known
  PROJECT PATH:LINE [--repository NAME]` scans the mirror at the base again and keeps only a place it finds, else 65.
  The project command group takes its project as an argument, as `clear` and `remove` do, rather than the plan's `-p`.
- Docs: `how-it-works.md` (what Porelo does not do), `faq.md` (a token or key in the repository), `project-file.md`
  (`secrets`), `commands.md` (`project secret-known`). Changelog `secret-scan-at-start.yml`.
- After the agy reading (gemini-4-argon-high), fixed: a `CommandException` (timeout, no git) passed `SecretCheck`, and
  under `--all-repositories` left an unscanned tree mounted; the runner's cut of an answer over 16 MiB dropped
  findings silently (now `-o`, and the cut is a failure); `secret-known` searched only the default branch while a
  start `--from` another searched that one (now `--from`, and the start's line names it); a scan that could not run
  was said under `refuse` as a workspace holding a credential; the page's example line had other spacing. Not
  changed: the landed work a `--reads` task fetches is not scanned, it is another task's work that came through the
  gate; a path holding a newline is named by its last part.
- Red first: `SecretScanTest` did not compile without `SecretScan`. The start tests in `TaskRunCommandTest`
  (`aProjectThatRefusesSecretsCreatesNoContainerForAWorkspaceHoldingOne`, `aProjectThatWarnsSaysTheFindingAndStarts`)
  failed with the call in `TaskLaunch` switched off, in the same reactor, and pass with it.
- Green, targeted: `CredentialShapesTest` 9, `SecretScanTest` 9, `SecretsKnownTest` 2, `SecretsSettingTest` 4, the three
  start tests, `WorkspaceSetupTest` 6, `CommandMaskTest` 29, `ToolCallsTest` 13, `ProjectReaderTest` 66,
  `ProjectFileFaultsTest` 112, `ReadingsDirectoryTest` 10, `RepositorySettingsTest` 4, `PoreloCliTest` 4.

## Red-first test

A new `SecretScanTest` in `gate` makes a bare repository whose commit holds a private key header in `deploy/key`.
It expects one finding with that path, line 1 and the kind `private key`, and no byte of the key in the finding or
in anything said. It fails today, since there is no scan. A second test in `apps/app-task` starts a task from such a
repository under `secrets: refuse` and expects exit 65 and no container; under `warn` it expects the `secret` line
and a start that goes on.

## Risks

- A false finding on every start annoys more than it protects. The shapes are the narrow ones masking already uses,
  and an acknowledgement on the host silences one for as long as its text stays.
- `git grep` over a large tree takes time at every start. It runs on the mirror, on one commit, with `-I`, so binary
  files are skipped. To be measured on a large repository.
- A credential in a form Porelo does not know is not found. The docs say the scan is a net for the known forms, not a
  proof of none.

## Blockers

None.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A finding acknowledged on the host is not said again while its text stays, and is said again once the text at
  that line changes. Seen to fail: an acknowledgement written into the repository's tree that silences a finding.
- Masking a command line still masks every shape it masked before the list moved.
- The documentation page that describes the area says it.

## Open questions

None.

## Checked on the VM (2026-11-14)

- ubuntu26.04 as `core`, snapshot.140: the new scenario "a start whose tree holds a private key is refused before any
  container, where the project says refuse" (`task-guarded.feature`) is green. The start says `secret
  deploy/key:1  looks like a private key; the agent can read it` and `(secrets: refuse)`, exits 65, leaves no
  container, and prints no byte of the key's body.
- Time: the VM's own mirrors hold at most 3 files, so a bare copy of porelo's `main` (3135 files, an 18 MB mirror) was
  scanned with the same `git grep`: 1.65, 1.90 and 2.12 s wall, 35 MB at most, on 6 CPUs.
- The same copy showed 105 places, 78 of them `sk-` inside longer words (`task-...`, `disk-...`). The scan now takes
  each token shape only where no token character comes before it (`CredentialShapes.Shape.bounded`); masking keeps
  the unbounded shapes. Porelo's own tree then gives 43 places, the fake credentials of its tests and docs.
  `SecretScanTest.aWordThatHoldsAShapeInsideItIsNoToken` pins it.

## To be checked

None.
