# B140 — A Secret Scan Of The Workspace At Start

**Status:** Estimated.
**Size:** M - a scan in the gate, the start, a project setting, an acknowledgement file and docs.

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

1. **The scan, in the gate:** `SecretScan` in `gate/src/main/java/org/fuin/sokar/gate/` runs `git grep -I -n -E` over
   the tree of the commit the task will check out, in the bare mirror, with Sokar's own list of known credential forms:
   private key headers, and the prefixes of the forges' and providers' tokens Sokar already knows (GitHub's, the
   providers under `providers/`). It answers `Finding(path, line, kind)`; the matched text is dropped where git's output
   is read and never kept, printed or journalled. The history the task receives is not scanned: a credential removed in
   an earlier commit is not found, and the docs say so.
2. **At the start:** `TaskLaunch`, after the mirror is current and before the container is made, calls it and says
   each finding as `secret    <path>:<line>  looks like <kind>; the agent can read it`. The project's
   `secrets: warn | refuse` (in `core/.../project/Project.java`, `warn` by default) decides whether the start goes on;
   `refuse` ends it with exit 65, as a start refused for want of a credential does.
3. **A false finding:** acknowledged in a list kept on the host beside the project, never in the repository, so the
   agent cannot add to it: `ProjectPaths.secretsKnown(project)`, `<state>/secrets-known/<project>.json`, owner-only,
   as `MessagingPaths.moderation` keeps a person's decisions about a project's peers. Each entry is `path:line` with
   a hash of the matched text, so it holds only while that text stays. `sokar project secret-known -p <project>
   <path>:<line>` adds one, and `sokar task start` names that command for each finding. Nothing in the repository
   acknowledges a finding.
4. **Docs:** `doc/how-it-works.md` and `doc/faq.md` (the token in the work tree, and that the history is not
   scanned), `doc/project-file.md` (`secrets:`), `doc/commands.md` (`project secret-known`).

Red first: a `SecretScan` test on a bare repository whose commit holds a private key header in `deploy/key` - no
finding today - then with the finding, its path and line, and no byte of the key in what is said.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A finding acknowledged on the host is not said again while its text stays, and is said again once the text at
  that line changes. Seen to fail: an acknowledgement written into the repository's tree that silences a finding.
- The documentation page that describes the area says it.

## To be checked

- Which detector: Sokar's own list of credential forms, as planned, or a library that keeps one, a dependency to
  add (Agent Core's).
