# B414 — A Developer Reads An Organization At GitHub

**Status:** Idea; decided by the operator on 2026-11-03: the founder's admin on this repository. Its state and place in the plan
are the Coordinator's.
**Size:** S to M - one module and its tests, but `IdentitySource.owns` is a contract and the decision changes who a first read
trusts.

**What must be true.** At GitHub, a person with push access to the organization's repository reads the organization from its
first commit, without being a member and without a token right on the organization's members. Whoever filed the first
`owners.yml` with write and no admin stays refused. When the forge gives no answer, the refusal says what it could not learn.

## What was measured

In a walk at GitHub, a developer's first write stopped at the first commit: `cannot-say`, "the forge could not say enough of its
people's keys and owners to read the organization to its tip: see whether this person's forge token is set". The token was set.

With that person's fine-grained token, on a private repository where they hold write:

| Call | Answer |
|---|---|
| `GET /orgs/<org>/memberships/<founder>` | 403 "Resource not accessible by personal access token" |
| `GET /user/memberships/orgs/<org>` | 403, the same words |
| `GET /repos/<org>/<repo>/collaborators/<founder>/permission` | 200, `admin` |

The owner's token answers all three with 200, `active` and `admin`. GitHub's documentation says of the first call that "the
authenticated user must be an organization member". The same check on Forgejo is another call, so a pass on a local forge says
nothing about this.

A host does not reach the question: `org fetch` holds a pin, and `TreeWalk.founding` returns before asking. Read, not run.

## The decision

The founder counts when GitHub says they have admin on this repository.

- **An owner always has admin on the organization's repositories**, so anything less answered plainly is a no.
- **What it gives up:** a repository admin who is no owner could found an empty repository. They can already rewrite that
  repository, and only an owner or an admin gives that right.
- **What it keeps:** a person with write and no admin stays refused. "Cannot say" stays no yes.
- **Declined:** only members of the organization read it, since every developer would need a membership and a token right;
  a pin of the founder's key each person brings, since it adds a step to joining.

The accepted risk goes into `doc/decisions.md` with this issue.

## Plan

Read in the code on 2026-11-03 (`organization/api`, `organization/git-tree`).

1. **The repository reaches the question**: `IdentitySource.owns` today takes the organization and the login; `TreeWalk` holds
   only `organisation`, and `ForgeAddress` drops the repository's name after matching it. The three `new TreeWalk` in
   `GitTreeStore` and `GitTreeWrites` pass it.
2. **`HttpForgeDirectory.gitHubOwns`**: where the membership gives no answer, ask the repository's permission for the login.
   `admin` is a yes, any other permission a no, every other answer "cannot say". A plain answer on membership stays first.
3. **`forgejoOwns`** stays as it is.
4. **The words** in `GitTreeWrites`, both places, and `doc/commands.md`: say what could not be learned, not the token alone.
   `doc/credentials.md` says what a token at GitHub must be able to read.
5. **Tests**: the stand-ins of `IdentitySource` follow the new signature.

**Red first:** `HttpForgeDirectoryTest`: memberships answer 403, the permission `admin`: a yes. Permission `write`: a no.
Permission 403 or 404: "cannot say".

## To be checked

The operator makes a second GitHub account, with no membership in `porelo-ai`, after the trial moves (2026-11-08). The
measurements below wait for it.

- What the permission call answers for a collaborator who is no member. The token measured belongs to a member of the
  organization, whose token lacked the right on members, so the 403s above say nothing of a non-member's, and the 200 does not
  yet show that a collaborator gets it. This case is what the issue is for.
- What the permission call answers with this token for a login with `write`, for one with no access, and for a token with read
  only. Only `admin` for an owner is measured, and the documentation names no token permission and no caller for it.
- Whether a read-only person's token gets an answer, and what they then see.
- Whether the walk's result is settled for a person who later gains the right, as `unsettled` handles a card.
- Acceptance at GitHub: a collaborator's fine-grained token with no right on members, which the walk that found this does not
  prove, since it gave its token that right.
