# B804 — The Credential Helper's Answer Is The Forge's API Token

```json
{ "$schema": "issue-1.json", "id": "B804", "title": "The Credential Helper's Answer Is The Forge's API Token", "state": "Backlog",
  "size": "XS", "priority": "High", "rank": 3, "blockedBy": [],
  "covers": "`porelo vault credential --for <organization address>` is stated, and pinned by a test, to answer with the token Porelo's own calls to that forge's API take, usable by a person's program against that API" }
```

**Why this size.** One paragraph in `doc/credentials.md` and one test beside the helper's; no product code unless the
test finds the two tokens differ.

**What must be true.** A person's own program, such as a planner that keeps the project's issues in the forge's issue
tracker, asks `porelo vault credential --for https://<forge>/<organization>/` and may use the answer against that
forge's API, because Porelo promises it is the same token Porelo's own calls to that API take. No task is ever lent it.

## Plan

- **`doc/credentials.md`**, under "A program reading a private repository": one bullet saying that for an
  organization's address the answer is also the token Porelo asks that forge's API with, and that the program may
  use it against that API; which rights it needs is the token's, chosen by the person, and Porelo checks none.
- **The test that goes red first**, `VaultCredentialForTest`: for an organization's `https://` address, the helper's
  answer equals the token the forge-API path takes (`OrganizationAccess`, as `ForgeCardProposals` is given it) in
  each case the two rules name - a credential declared for the address, `git.token.<host>`, and only `git.token` in
  the vault.
- Where the test shows a difference, the helper follows the forge-API rule for that case and the doc says so.

## Acceptance

- The doc paragraph is published with the docs; the test passes and fails when either side's choice of token changes.

## To be checked

- Whether the forge-API path falls back to a bare `git.token` as the helper does, and how a credential declared with
  `--env` or `--file` reaches the helper, which runs as git's child; read in `OrganizationAccess` and
  `VaultCredentialCommand`.
