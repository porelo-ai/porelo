# B325 — Tasks Started Together Share One Mirror Clone

**Status:** Idea.

**What must be true.** Two tasks of one repository started at the same moment, before its mirror
exists, both get the git gate: the second waits for the clone the first makes, or makes it itself
once the first has failed, and neither starts with a workspace that holds the upstream's files but
no `.git`.

Seen on `fedora` (2026-10-29): of two `porelo task start ... -r ij-walk` started together, the
second printed `no git gate for this task: 'git clone --bare ...' failed ... applypatch-msg.sample
... exists` and started without a gate.

## Acceptance

- Two starts of one repository whose mirror does not exist, run at once, both end with a gated
  workspace; the mirror is cloned once.
- A clone that fails for the first start is not taken as a mirror by the second; the second clones
  again or reports git's reason for its own attempt.

## Open questions

- A lock beside the mirror held for the clone, or a clone into a temporary directory renamed into
  place, which the second start then finds.
