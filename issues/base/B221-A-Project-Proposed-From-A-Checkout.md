# B221 — A Project Proposed From A Checkout

**Status:** Analyzed; blocked by [B220](README.md#b220) and [B192](README.md#b192).
**Size:** L - a merge and two draft writers, an export of a mirror's tree that nothing makes today, a recorded state
per repository, two daemon methods (one streamed) and a field, a command, the question at `task start`, and one more
source in [B192](README.md#b192)'s order; several modules and a contract the interface reads.

**What must be true.** `porelo project init <checkout>` asks every installed build-tool plugin what it recognizes,
merges the answers into a tools draft (`.porelo.yml`) and, where anything needs a rule, a `project.yml` draft, each
value with its source as a comment, and names where two plugins disagree instead of choosing; no credential is copied
into a draft, and the checkout's tracked files stay unchanged. Porelo's share of
[PJ52](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj52).

## The daemon's methods, agreed with the interface

- **A repository in `default` added by its address has no checkout**: only the gate's bare mirror holds it
  (`DefaultProject.Source.REMOTE`). The plugins then read the mirror's default branch, exported read-only into a
  directory of the host's, and its tools draft goes to `~/.config/porelo/projects/default/<repository>/.porelo.yml`,
  which [B192](README.md#b192)'s order for `default` does not read yet.
- **"The person's own files" are those of the account `porelod` runs as**, on the machine, which the interface may
  not run on; each value's origin says `YOURS` (that account's), `CHECKOUT` or `MACHINE`, and the answer names the
  machine.
- **`DefaultRepository` gains `proposal`**: `UNASKED`, `ASKED` or `CONFIGURED` (`porelo.yml`, `.porelo.yml` or a
  followed project naming it), so no client derives whether to ask.
- **`ProposeProject(project, repository)`** streams one progress reply per plugin and a final one: tools and rules
  as two lists of `{key, value, file, line, origin, unknown, note}`, the disagreements, the cost of leaving out the
  rules as a sentence, the `.porelo.yml` and `project.yml` texts as they would be written, and each credential id
  with its `storeCommand` and `storeStdin`, built as `CredentialDeclare` builds them
  (`CredentialDeclarations.storeCommandFor`); `Credentials` carries no such field.
- **`TakeProposal(project, repository, choice)`** with `TOOLS`, `TOOLS_AND_DRAFT` or `NONE` writes `.porelo.yml` on
  the machine, records the repository as asked, and under `TOOLS_AND_DRAFT` answers the `project.yml` text; no
  client writes either file.
- **An older daemon answers `MethodNotFound`**, which a client reads as "this machine cannot propose".

## Plan

Read in the code on 2026-10-20 (`apps/app-project`, `apps/app-model`, `daemon`).

1. **One implementation, two renderings**, as `vault authorize` and the daemon's `Authorize` share `Flow`:
   `ProjectProposal.propose(project, repository, Consumer<PluginDone>)` in `app-project`; `porelo project init`
   prints each step, `ProposeProject` sends it as `replies.more` and ends with `replies.last`, refusing a call that
   does not stream with `StreamRequired`, as `Authorize` does (`VaultMethods.java:535-600`).
2. **The directory the plugins read**:
   - a checkout (`init <checkout>`, or a `default` entry of source `CHECKOUT`): its working tree, as the person sees
     it, never written;
   - a `REMOTE` entry or a followed project's repository: the mirror's default branch (`git symbolic-ref HEAD` in
     the bare mirror, `GateSupport.mirror`, `:126`), exported with `git archive | tar -x` into a fresh `0700`
     directory under `$XDG_RUNTIME_DIR`, removed after. `MirrorTree.export` is new; nothing exports a tree today.
     Branches named `porelo/*` are never read.
3. **The merge**, `ProposalMerge`: the plugins' findings by `part` and `key`; one value kept with every source; two
   values for one key are a disagreement, listed and left out of the draft; unknowns listed with where they are set;
   a credential named by id only, its value never asked of a plugin. The cost of leaving out the rules is one
   sentence composed here.
4. **Two writers**, both hand-built text as `DefaultProject.fileHeld` (`:336`) builds its file, each value followed by
   its source as a comment (`# pom.xml:14, from the checkout`):
   - `ToolsDraft` in [B192](README.md#b192)'s format (`image:` with `base_image`, `snippet`, `env`), read back
     through [B192](README.md#b192)'s reader before it is shown;
   - `ProjectDraft`, a `project.yml` naming the repository under `repositories`, read back through `ProjectReader`
     and the checks `CheckProjectFile` runs.
5. **The recorded state**: `DefaultProject.Entry` gains `proposal`, stored in `repositories.json`, read as `UNASKED`
   when absent, written under the file's lock as `add` is; `CONFIGURED` is computed, not stored - a `porelo.yml` or
   `.porelo.yml` where [B192](README.md#b192) reads them, or a followed project naming the repository. `asMap()`
   carries it into `DefaultRepository`.
6. **`TakeProposal`**: `TOOLS` and `TOOLS_AND_DRAFT` write `.porelo.yml` - into the checkout with a line in its
   `.git/info/exclude` for a `CHECKOUT` entry, else to `~/.config/porelo/projects/default/<repository>/.porelo.yml`
   (computed beside `ProjectPaths.defaultProjectFile`, `:88`) - and record `ASKED`; `NONE` records `ASKED` only.
   [B192](README.md#b192)'s order for a `REMOTE` entry of `default` gains that file as its first source.
7. **The daemon**: `20-project.varlink` gains the two methods, the types `Proposal`, `ProposedValue` and
   `Disagreement`, and `DefaultRepository.proposal`; `ProjectMethods` registers them; `InterfaceDescriptionTest`
   holds the part.
8. **The command line**: `ProjectInitCommand` beside `ProjectDefaultCommand`, in `ProjectsCommand`'s list,
   `init <checkout | repository>`, showing the drafts and asking which to take at a terminal, `--take
   tools|all|none` without one. `task start` in `default` on an `UNASKED` entry asks the same before the first task
   at a terminal, and without one starts as today with one line saying the drafts wait in `project init`.

**Red first:** `ProjectProposalTest` with fake plugins: a finding carrying a `<server>` password from a
`settings.xml` fixture never appears in either draft or the answer (seen to fail with a merge that copies values
through); two plugins naming two base images give a disagreement and no `base_image`; the checkout's tracked files
are byte for byte unchanged; a `REMOTE` entry's export holds the default branch, not a `porelo/*` branch.

## Acceptance

- `porelo project init <checkout>` with the stub plugin prints the tools draft and, where the stub reports a rule,
  the `project.yml` draft, each value with its file and line, and changes no tracked file.
- A `default` entry added by address is proposed from the mirror's default branch, and `TOOLS` writes its
  `.porelo.yml` under `~/.config/porelo/projects/default/<repository>/`, which the next start's image reads.
- After `NONE`, `DefaultRepositories` answers `ASKED` and `task start` asks nothing.
- A credential's value appears in no draft and no answer.

## To be checked

- How `porelo` matches a checkout to an entry under `repositories` (its `origin` against `upstream`), so the
  `project.yml` draft names the repository the next start recognizes.
