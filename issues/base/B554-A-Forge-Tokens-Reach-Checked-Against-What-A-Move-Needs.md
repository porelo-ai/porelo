# B554 — A Forge Token's Reach Checked Against What A Move Needs

```json
{
  "$schema": "issue-1.json",
  "id": "B554",
  "title": "A Forge Token's Reach Checked Against What A Move Needs",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "A forge token's permissions checked against what a move asks of it, each missing one named, before a server is rented."
}
```

**Why this size.** one more need in the founding's check, read-only, from the forge's own answers.

Filed 2026-11-09 for the operator's automated rehearsal of a move on a rented server, run by the
agents under a test identity whose token the operator makes once; built the same day on the operator's order to build
the rehearsal now.

**What must be true.** Before a move, a person learns from porelo, without changing anything, whether their forge token
reaches what the move will ask of it in the organization: reading who owns it (members), making a project's
repository (administration), adding a host's read key to a repository (administration), pushing to and fetching from a
repository (contents), and changing a workflow file where the project has one (workflows). Each missing permission is
named with its forge's own name for it, so the token can be made right once, before the server is rented.

## Why

A token made without one of these permissions fails late: a missing members read in the first read of the
organization (403, `cannot-say`), a missing administration write at the project's opening or when the host is prepared
(422), a missing workflows write at the first push of a workflow change. In a rehearsal on a rented server each such
failure costs a rental. `porelo org create --check` already asks the forge whose the token is (`token`, `owner`); it
does not say what the token may do.

## Plan

- `org create --check` (and the same need in a project's founding check) gains `token-reach`: `ready` when the forge's
  answer for the token covers each permission above, `missing` naming each one lacking, `cannot-tell` where the forge
  does not say a token's permissions (then the check says which call would show it).
- Read from the forge's answers about the token itself and a read-only request per permission; nothing is made.
- Red first: a token answer lacking administration write reads `missing` with that permission named; a full one
  `ready`.

## As built

- `HttpForgeDirectory.tokenReach(organization, repository)` answers one reach per need (members, administration,
  contents, workflows), each `READY`, `MISSING` or `CANNOT_TELL` with the forge's own name for the permission. It
  only reads, by the reads the move itself makes or the nearest to them.
  - **GitHub:** members by the founding's own call (`/user/memberships/orgs/{org}`); administration by the
    repository's deploy keys; contents by its commits (an empty repository's 409 is a read).
    - A classic token names its scopes in `X-OAuth-Scopes`: a lacking `read:org` (or `write:org`, `admin:org`),
      `repo` or `workflow` is missing. A held scope is ready only once the read shows the organization lets the
      token in, so single sign-on or another account's token is not said ready on its scopes.
    - A fine-grained token's permissions are named nowhere: a refused read is missing; a granted read is ready only
      for members, where reading is all the move asks, and otherwise leaves the write `CANNOT_TELL`. Workflows is a
      write alone, always `CANNOT_TELL`.
  - **Forgejo:** members by the founding's own call (`/users/{login}/orgs/{org}/permissions`), administration
    (`write:organization`, which making a repository in an organization takes) by reading the organization, contents
    and workflows (`write:repository`) by reading the repository. A 403 is missing only when it names a scope, and the
    scope it names is the one said.
  - **Only a refusal for want of the permission is missing:** a 403 that carries a rate limit
    (`X-RateLimit-Remaining: 0`, `Retry-After`) or GitHub's single sign-on (`X-GitHub-SSO`) is `CANNOT_TELL`. A forge
    down, or a token it does not take, is `CANNOT_TELL` for every need.
- `org create --check` asks it once the token is taken, or taken without saying whose (an app's token), and says one
  need `token-reach`. It is `missing` naming each lacking permission with what the move does with it; else
  `cannot-tell` naming each untold one with the step of a move that would show it; else `ready`. Only `missing` makes
  the answer not ready. An address whose names the forge's API would not take asks nothing and is `cannot-tell`.
- There is no project founding check with needs today, so the need is in `org create --check` alone.
- `ForgeAddress.repository()` names the address's repository, without `.git`.
- Seen red first: each forge reach test with the rule it tests broken (the 403 rule, a classic scope read as held,
  the members read by the public list, a rate limit's 403 taken as a lack, any Forgejo 403 taken as a lack); the
  check's tests with the reach not asked, and with an app's token not asked.

## After the code reading

agy (gemini) read the first build. It found that the public member list answers any token, so members was read the
wrong way; that Forgejo's ownership read and repository making take other scopes than those probed; that a rate
limit's or single sign-on's 403 was taken as a lack; that a classic token was judged on its scopes alone; and that an
address with `..` in a name threw. Each is fixed above, with a test seen red. Left as said: a fine-grained token's
writes stay `CANNOT_TELL`, since GitHub names them nowhere, and a 404 on a repository the token cannot see is
`CANNOT_TELL`, not missing, because the repository need says separately whether it is there.
