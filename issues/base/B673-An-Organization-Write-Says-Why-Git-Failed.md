# B673 — An Organization Write Says Why Git Failed

```json
{
  "$schema": "issue-1.json",
  "id": "B673",
  "title": "An Organization Write Says Why Git Failed",
  "state": "Review",
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

## Built (2026-11-12)

- `OrgWriting` and `OrgProposing` put the store's own words first: the failing step and, where git ran, git's own
  lines as `GitTreeStore.Unreachable.fromGit` prepared them. Then they say, as before, that the change may have been
  made and to ask again, with the same `--id` for a write. A push that was not taken already said so.
- The words pass through `ForgeAddress.withoutUserinfoIn` first, so an address with user information in git's
  words never reaches the answer. A test pins it with a password in an address.
- `org person proposals` names the step as well, pinned in `OrgCardProposalCommandsTest`.

## Acceptance

- The refusal names the failing step and git's words; a test fails while they are dropped.

## Read by a second model (2026-11-12)

- A value of several lines in a token file, variable or vault entry would have put its second line on git's standard
  error, which git quotes as `invalid credential line`, and so into this refusal. `porelo vault credential` now
  refuses a username or secret with a line break or a NUL and hands git nothing; a test pins it.
- The tests now assert the whole tail of the refusal, ask again with the same `--id`, on both paths.
- Not changed: an address with `%40` in place of `@` is left as it is by `withoutUserinfoIn`; an organization's
  address with user information is refused where it is set, so no such address reaches git here.
- Not changed here: `OrgCreateCommand`, `IdentityOrganization.filed` and `ProjectCreateRepositoryCommand` still answer
  without git's words; they are other commands than the one seen.
- From the first VM run: git's lines ran together where the newline was made printable ("'/bin/false'?fatal:").
  `fromGit` now joins them with "; ".
- Codex read that and found three Reds, fixed: git's "invalid server response; got '...'" quotes what the forge
  answered, which may echo the lent token, so such a line is dropped; an address cut at 400 characters could lose its
  `@` and keep its user information, so it is redacted before the cut; and a credential declared in a variable git
  reads itself (`GIT_`, `SSH_`, `LD_`) is never handed to git under that name. Each has a test that failed before.
- Kept, from that reading: the credential helper does not hold a line to git's 65,535 bytes; git took longer lines,
  and no value leaks.
- Codex read that again and found three more Reds, fixed: git's words dropped only lines of known shapes, so a forge
  that echoed the lent value in another line ("protocol error: unexpected '...'", a second line after `ERR`) still
  put it in the refusal. Each lease now names the values it lent: the token as is, stripped, URL-encoded and as
  Basic. These are taken out of git's words before anything else reads them. The variable guard now also refuses
  names that steer git's transport or the process (`*_PROXY`, `NO_PROXY`, `CURL_`, `SSL_`, `XDG_`, `HOME`, `PATH`
  and the like). And a standard error tail cut midway into a line keeps none of that line, so a token at its end
  cannot outlive the cut. Each has a test that failed before.
- Kept: the guard is a list of refused names, not one allowed prefix; `credentials declare --env` takes any name
  today, and names such as `GITHUB_TOKEN` stay usable.
