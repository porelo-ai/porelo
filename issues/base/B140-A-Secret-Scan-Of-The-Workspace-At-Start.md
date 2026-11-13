# B140 — A Secret Scan Of The Workspace At Start

```json
{
  "$schema": "issue-1.json",
  "id": "B140",
  "title": "A Secret Scan Of The Workspace At Start",
  "state": "Analyzed",
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

## To be checked

- How long the scan takes on a large repository's mirror, measured on the test VM.
