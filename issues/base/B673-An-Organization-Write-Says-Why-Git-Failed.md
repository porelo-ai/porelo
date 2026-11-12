# B673 — An Organization Write Says Why Git Failed

```json
{
  "$schema": "issue-1.json",
  "id": "B673",
  "title": "An Organization Write Says Why Git Failed",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A command that writes the organization and cannot reach its repository names git's own words and the step that failed, not only that the organization cannot be reached."
}
```

**Why this size.** one refusal in `OrgWriting` and its twin in `OrgProposing`, and a test; no contract.

Filed 2026-11-12 from an operator test's clean-up, straight into the backlog as the bug rule says.

**What was seen.** `porelo project sign <project> --yes --base <commit> --id <id>` answered "the organization cannot
be reached, and the change may have been made: ask again with --id <id>" (exit 69), again on asking again, while
`porelo org --json --refresh` read the organization fresh. Nothing said which git step failed or why, so the person
could not tell a missing push right from a forge that did not answer.

**Found.** `OrgWriting` (`catch (GitTreeStore.Unreachable ex)`) builds its refusal without `ex.getMessage()`, which
carries the step ("cannot read the organization's repository", "the repository cannot be reached", "did not answer
in time") and git's own words, cut and made printable by `GitTreeStore.Unreachable.fromGit`. `OrgProposing.answer`
drops them the same way. `porelo project sign` runs this in its own process; porelod is not involved.

**What must be true.** The refusal keeps saying that the change may have been made and to ask again with the same
`--id`, and also names the failing step and git's own words, as `fromGit` already prepared them.

## Plan

- `OrgWriting` and `OrgProposing`: put `ex.getMessage()` after the sentence, as the `NotTaken` branch already does.
- Red first: a test whose store throws `Unreachable.fromGit("cannot read the organization's repository", "fatal:
  could not read Username")` and asserts the refusal names both.

## Acceptance

- The refusal names the failing step and git's words; a test fails while they are dropped.
