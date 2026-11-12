# B517 — A Card Proposed, And Accepted By An Owner

```json
{
  "$schema": "issue-1.json",
  "id": "B517",
  "title": "A Card Proposed, And Accepted By An Owner",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "A person who cannot write the organization's repository files their card as a proposal, which grants nothing until an owner accepts it; the window's *Join an organization…* and *Accept a proposed card…* wait on it."
}
```

**Why this size.** L: a proposal's place on the forge, which needs fork, close and branch calls the forge clients
porelo uses do not have, a reading of waiting proposals that judges each as the readers will, an admin's acceptance,
and the CLI for both sides.

Backlog since 2026-11-09, in ST295. Filed 2026-11-08. Its plan reading comes first.

**What must be true.** A person who cannot write the organization's repository can still ask to join it.
- They file their card as a **proposal**, signed through their own ssh-agent as `identity create` signs a card.
- An **owner** sees the waiting proposals and accepts one, which files it as the person's card, or turns it down.
- Until it is accepted, the proposal grants nothing: no role, no project, no host.
- A person who can write the repository files their card as today, with no proposal.

## Why

Agent Frontend's window shows *Join an organization…* and *Accept a proposed card…* as waiting actions. Today a
card is filed only by its own person's `identity create`, which needs write access to the organization's repository.
`CardStanding.notYet` is no waiting proposal: it is a card filed and accepted before the identity source could confirm
the login. Nothing proposes and nothing accepts.

## Plan

Read in today's code: `organization/git-tree` `TreeWalk` (first-parent walk, `people/<login>.card` counts from its
own key), `vault` `PersonCard` (the card's own signature inside the file), `apps/app-vault` `IdentityCreateCommand`,
`apps/app-organization` `OrgWriting` (signed commit, lent forge token, push), `OrgPersonCommand` (`revoke`,
`replace`), `ForgeRepositoryMaker` (GitHub and Forgejo APIs), `apps/app-model` `HostPullRequests`; `doc/roles.md`.

**Where a proposal is kept.** On the organization's forge, as a pull request from a fork in the person's own forge
account: one commit on `main`'s tip that adds `people/<login>.card` and nothing else, signed through the person's own
ssh-agent by the key the card names, on a branch `porelo-card-<login>`. A person who can read the organization's
repository and holds a forge account reaches it with the forge token their vault already lends; nobody needs write
access to the organization's repository to propose.

**Why this place, and no new rule for the readers.** The readers already take that commit: `TreeWalk` counts a
person's card from its own key. What stops the person today is write access alone. So acceptance is the person's own
commit, unchanged, pushed to `main` as a fast-forward by someone who may write there. No reader rule changes, no role
gains a right, and a machine on an older porelo takes the accepted card as it takes any card today. A merge commit, a
squash or a rebase would carry the admin's signature on the card's path and be refused (unless the login had a
refused card before, where an organization admin may file one in its place), so acceptance never makes one.
The other way, an organization admin filing a self-signed card in their own commit, would need a new rule in
`TreeWalk` that every machine must learn first; it is not taken.

**Grants nothing until accepted.** The readers walk `main` alone; a pull request is not on it. No role file is
touched by a proposal.

1. **The forge's side.** One client for fork, branch push, open, list, close with a comment and fetch a pull
   request's head, on GitHub and Forgejo, over the token the vault lends as `OrgWriting` does. Red first: a fake
   forge sees one fork, one branch and one open pull request with base `main`.
2. **`porelo org join`.** On the organization this machine follows. Where the forge says the person may write the
   repository, it files the card as `identity create` does, with no proposal. Otherwise it makes the one commit on
   `main`'s tip, pushes it to the fork's `porelo-card-<login>` and opens the pull request, or updates it when one is
   open; run again after `main` moved, it remakes the commit on the new tip. `--withdraw` closes the person's own.
   `--json` answers `{state: filed | proposed | stale | accepted | declined | withdrawn, pullRequest}`. Red first:
   a person without write access leaves `main` unchanged and one pull request open; one with write access opens none.
3. **`porelo org person proposals`.** The open pull requests from a `porelo-card-*` branch, each judged before it is
   shown: its author is the login the path names, it is one commit, it adds that one path, absent on `main`, the commit
   is signed by the key the card names, the card's own signature holds and its days are valid. `--json` answers each
   with login, name, key fingerprint, pull request, opened, and `ready`, `stale` (main moved) or `refused` with the
   reason. Red first: a proposal signed by another key, or adding a second path, is `refused` and named why.
4. **`porelo org person accept <login>`** and **`decline <login>`.** An organization admin's, an owner's while
   `organization-admins.yml` is absent, as `doc/roles.md` gives cards. Accept judges as 3 does, refuses anything not
   `ready` and changes nothing, then pushes the person's commit to `main` as a fast-forward with
   `--force-with-lease` on the tip it judged; the forge marks the pull request merged. Decline closes it with a
   comment. Red first: an accepted card is read by `TreeWalk` as the person's, signed by their own key; accepting a
   `stale` one leaves `main` unchanged; a developer's accept is refused before anything is pushed.
5. **Nothing granted.** Red first: a repository with an open proposal answers no such person, role, project or host.
6. `doc/roles.md` and `doc/commands.md` on proposing and accepting, a decision in `doc/decisions.md` (why a
   fast-forward of the person's commit, not an admin's rule), the changelog. The JSON shapes of 2 and 3 are told to
   Agent Frontend in the channel before they land, for *Join an organization…* and *Accept a proposed card…*.

All tests are unit tests over a fake forge and local repositories.

**Who accepts.** The issue says an owner; `doc/roles.md` gives cards to organization admins, and to owners only while
the list is absent. The plan follows `doc/roles.md`.

## After the plan reading

agy (gemini) read the plan against the code; the build takes these answers:

- **Judged as the readers will.** `proposals` and `accept` judge a proposal by applying its commit to a scratch clone
  of `main` and walking it with `TreeWalk`, as `GitTreeWrites` judges a write before its push, so the forge's word on
  the login's key, a revoked key and a refused path are judged exactly as every machine will judge them. Beside the
  walk: the card names no `predecessor` (a first card), its key is no other login's (`keyHolder`), and today lies
  between its `created` and `expires`.
- **Stale is seen before accepting.** `proposals` answers `stale` when the commit's parent is not `main`'s tip. Each
  acceptance makes every other open proposal stale, since the person's signature covers the parent; `join`, run
  again, signs anew on the new tip. Accepted as the cost of no new reader rule; proposals are rare.
- **The acting admin is checked.** `accept` and `decline` make no commit of the admin's, so on a fresh read the acting
  login must be an organization admin (an owner while the list is absent) and the key in the agent must be that
  login's trusted, unrevoked key, before anything is pushed or closed. The role check is porelo's: anyone the forge
  lets write `main` could push the same commit, which files only the person's own card and grants no role.
- **The pull request is bound to its person.** A proposal is taken only from branch `porelo-card-<login>` opened by the
  forge user `<login>`; any other pull request touching the path is not a proposal.
- **The forge's side, in porelo.** The forge clients porelo takes (`porelo-forge-client-github`, `-forgejo`) open,
  list and read pull requests but cannot fork, close one or delete a branch. Those calls are made in porelo over
  `HttpClient` and `Json`, as `HttpForgeAdmin` does, rather than waiting for a forge-clients release.
- **A branch in the organization's repository first.** Where the person may push a branch but not `main`, `join` pushes
  `porelo-card-<login>` there; only a refused branch push forks, polling GitHub's fork until it answers.
- **`join` in `app-organization`, from the kept card.** `app-organization` does not depend on `app-vault`. `join` takes
  the card `identity create` keeps under the identity directory (it keeps it before filing it), as `OrgWriting`
  reads it, and signs the commit through the agent as `OrgWriting` does; with no kept card it says to run
  `porelo identity create` first. `identity create`, refused at the push, names `porelo org join`.

## As built

- `CardProposals` (git-tree) judges a proposal as the plan says: the person's commit is fetched from the organization's
  own pull ref onto a scratch clone of `main`, walked with `TreeWalk`, and pushed unchanged as a fast-forward of the
  judged tip, with a lease. A proposal is taken only from the person's own fork or the organization's repository,
  from branch `porelo-card-<login>` opened by `<login>`.
- `ForgeCardProposals` (app-organization) makes the fork, open, list, read and close calls for GitHub and Forgejo over
  `HttpClient` and `Json`. The token goes only into the `Authorization` header of its own forge, redirects are not
  followed, and errors name only the status. `OrgProposalAccess` lends the token to git through the credential
  helper, and only for the organization's own repository.
- `porelo org join` (with `--withdraw`), `porelo org person proposals`, `accept <login>` and `decline <login>`.
  Accepting closes the pull request with a comment when the forge did not mark the plain push merged.
- `identity create` keeps a first card once the repository refuses its push, not before, and then names
  `porelo org join`. A refusal before any push still keeps nothing.
- A proposal command that cannot reach or read the organization's repository answers `cannot-say` (69), as every
  other organization command does.

## After the code reading

Read by agy (gemini-4-argon-high). Each finding was checked against the code; each one taken has a test seen red.

- Taken (red): `identity create` kept the card before filing it. A refusal before any push then left the card on
  disk, and a local write failing after the push came before filing. Three existing `IdentityCreateCommandTest`
  cases failed. The card is now kept at the refused push alone; `IdentityJoinTest` checks that.
- Taken (red): a pull request from a third account's fork, or from a fork the forge no longer names, was listed and
  could be accepted. The commit is still judged in full, but a proposal now comes only from the person's own fork or
  the organization's repository.
- Taken (yellow): `GitTreeStore.Unreachable` escaped the proposal commands as `failed` (70); it is now `cannot-say`
  (69).
- Taken (yellow): the fork's address was found by replacing the first `/<organization>/`, which rewrote a host that
  bears the organization's name. Now only the owner segment before the repository changes.
- Not taken: `list` reads every open pull request once more before filtering by branch. Proposals are few, and a
  pull request read afresh is what is judged.
- Not taken here: a `CREATE` operation left pending by a refused push stays pending after the card is accepted
  through a proposal. `identity renew` and `rotate` then refuse and name going on with that id. Named under To be
  checked.

## To be checked

- An operation `identity create` left pending at a refused push, once its card is accepted through a proposal:
  `identity show`, `renew` and `rotate` should take the accepted card as that operation's end.
- A private organization repository: the person must be able to read it to fork; one who cannot is told so, and no
  other way in is built.
