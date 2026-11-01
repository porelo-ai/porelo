# B386 — A Project Access Contract Without Guessing

**Status:** Idea.
**Severity:** low.
**Size:** S, proposed - one read-only lookup verb, two sentences in `doc/commands.md`, one defined answer of `access
add`, and one field in `project verify --json`, each with its test.

**What must be true.** A program that files and delivers access changes through `porelo project access` and checks them
with `porelo project verify` has nothing to guess:

- A read-only lookup answers which commit of the access repository carries a given `Porelo-Change-Id`, or that none
  does, so a caller that lost the answer of a filing finds its commit without searching the history itself.
- `doc/commands.md` says which head `project verify` takes from a bundle (today its `HEAD`).
- `project access add --role admin` for a person already listed as admin has a defined, documented answer: the change
  already in force, answered as done, or a refusal with its own reason.
- `project verify --json` names the signer of the access commit it verified, beside the project tip's signer.

## Acceptance

- Tests: the lookup finds the one commit with the id, answers none for an unknown id, and refuses an id carried by two
  commits; `add` of an existing admin answers as documented; verify's answer names the access commit's signer. Each
  seen to fail first.
