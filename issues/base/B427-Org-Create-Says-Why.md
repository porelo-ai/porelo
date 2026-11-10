# B427 — Creating An Organization Says Why It Failed

```json
{
  "$schema": "issue-1.json",
  "id": "B427",
  "title": "Creating An Organization Says Why It Failed",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 18,
  "blockedBy": [],
  "covers": "A failed `org create` says git's own reason and tells a refused push from an unreachable forge, with nothing the forge said passed on."
}
```

**What must be true.** A person whose `porelo org create` fails, at the command line or in the window, reads what
actually went wrong: git's own reason for a repository it could not read, and a separate answer for a repository that
was reached and did not take the push, each naming what to do next, with nothing the forge said passed on.

## Why

- **The reason is dropped when the empty repository is asked.** `TreeRepository.founding` throws
  "cannot read the organization's repository" without git's standard error, while `TreeRepository.fill` adds git's own
  lines through `gitsOwnWords`. No token offered, a dead token and a network failure therefore read alike, and neither
  `failures.log` nor the daemon's journal holds anything.
- **A refused push reads as an unreachable forge.** `GitTreeStore.NotTaken` extends `Unreachable`, and
  `OrgCreateCommand` passes every `Unreachable` to `OrgAccess.unreached`, which replaces its message. "See whether this
  person may push there" never reaches the person, though `OrgWriting` does keep it for every other change.
- **`OrgAccess.unreached` drops the words of everything but an unknown host key**, so a reason in the exception's
  message stops there; its `otherwise` text is all a person sees.

## Acceptance

- With no token offered, a dead token, a read-only token and a network failure, each against a stand-in forge, the
  answer differs and names the next action.
- A forge's `remote:` lines holding a token do not appear in the answer, nor does the token: seen to fail first.
