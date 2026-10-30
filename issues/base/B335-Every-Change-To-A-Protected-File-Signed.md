# B335 — Every Change To A Protected File Signed

**Status:** Accepted.
**Size:** M - a guess before analysis: the follow's check moves from the tip to each protecting commit, on both
repositories, with its refusal, docs and an acceptance step.

**What must be true.** A machine following a project applies a new `main` when every commit that changes a protected
file is signed by a key in `signers` as the list stood before that commit; any other commit needs no signature. A
developer changes the rest of the project's repository - plans, issues, documents - on their own, and their pull
request, merged by fast-forward on the host's forge, is applied without anybody signing on top of it.

The check, replacing today's check of the fetched tip alone:

- From the new tip back to the commit in force, along the first parent, each commit is compared with its first parent.
- A commit that changes, adds, deletes or renames a protected file must carry a valid signature by a key in force
  before it, as a change to `signers` is checked today. Every such commit, not only the last: otherwise an unsigned
  change could be carried through by a later signed commit that touches another line.
- One such commit without a valid signature refuses the whole new `main`. The commit in force stays, and the refusal
  names the commit and the file, as a value a program reads (`following --json`) and on a line of its own.
- The new tip still descends from the commit in force, or waits for `--accept-rewrite` as today.

The protected files of the project's repository, at each commit compared, whatever the commit before named:

- `project.yml`;
- `access.yml`, where `access: {file: access.yml}` puts it beside the project file;
- `machine-signers`, beside the project file, whose keys every following machine accepts messages from;
- every file an `image.snippet_file` names, for the project and for each repository, since what it holds runs as
  root in every task image.

The access repository holds `access.yml` alone, and the same rule applies there: every commit along the first parent
that changes it is signed by a key the project's chain holds, so an unsigned change cannot be carried by a later
signed one either.

## Acceptance

- A developer's unsigned commit that changes only a document, followed by nothing else, is applied.
- An unsigned commit that changes `project.yml`, under a later signed commit that changes only a document, refuses the
  new `main` and names that commit and `project.yml`; the same for `access.yml`, `machine-signers`, a named snippet
  file and the access repository's `access.yml`.
- A commit that renames or deletes a protected file is checked as a change to it.
- A commit signed by a key added to `signers` in that same commit is refused, as today.
- Seen to fail first: today's follow refuses the developer's unsigned document commit at the tip, and applies an
  unsigned change to `project.yml` that a later signed commit covers.

## To be checked

- Whether anything else the project's repository holds is read by a following machine; each one found is protected
  the same way, or the protection is bypassed through it.
- How a merge commit on the first-parent path is compared: against its first parent, so what a merged branch changed
  counts as changed by the merge commit.
