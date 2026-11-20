# B804 — The Credential Helper's Answer Is The Forge's API Token

```json
{ "$schema": "issue-1.json", "id": "B804", "title": "The Credential Helper's Answer Is The Forge's API Token", "state": "Review",
  "size": "XS", "priority": "High", "rank": 3, "blockedBy": [],
  "covers": "`porelo vault credential --for <organization address>` is stated, and pinned by a test, to answer with the token Porelo's own calls to that forge's API take, usable by a person's program against that API" }
```

**Why this size.** One paragraph in `doc/credentials.md` and one test beside the helper's; no product code unless the
test finds the two tokens differ.

**What must be true.** A person's own program, such as a planner that keeps the project's issues in the forge's issue
tracker, asks `porelo vault credential --for https://<forge>/<organization>/` and may use the answer against that
forge's API, because Porelo promises it is the same token Porelo's own calls to that API take. No task is ever lent it.

## Plan

- **`doc/credentials.md`**, under "A program reading a private repository": one bullet saying when, for an
  organization's address, the helper's answer is the token Porelo asks that forge's API with - a token declared for
  the organization's address, from the vault, a file or a variable, or with none `git.token.<host>` on the default
  port - and that the program may call that API with it. It names what the helper answers that is git's alone: a
  bare `git.token`, a declared user and password, and `git.token.<host>` on a forge's own port; and that a token
  declared for one repository is not what the helper answers for the organization. Rights are the person's choice.
- **Built and measured**: `VaultCredentialForTest` pins each case against `OrganizationAccess.forgeToken`: the token
  declared in the vault and in a file, `git.token.<host>`, and the three that are git's alone. Each was seen red with
  the API's rule broken on purpose. No product code changed: the reading of the change found the cases where the two
  differ, and the document now says them instead of promising sameness.

## Acceptance

- The doc paragraph is published with the docs; the test passes and fails when either side's choice of token changes.
