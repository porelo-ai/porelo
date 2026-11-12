# B517 — A Card Proposed, And Accepted By An Owner

```json
{
  "$schema": "issue-1.json",
  "id": "B517",
  "title": "A Card Proposed, And Accepted By An Owner",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "A person who cannot write the organization's repository files their card as a proposal, which grants nothing until an owner accepts it; the window's *Join an organization…* and *Accept a proposed card…* wait on it."
}
```

**Why this size.** a proposal's place in the organization's tree, a reading of waiting proposals, an owner's acceptance, and
the CLI for both sides.

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
squash or a rebase would carry the admin's signature on the card's path and be refused, so acceptance never makes one.
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

## To be checked in the plan reading

- That a fast-forward of the person's commit is judged exactly as their own `identity create` push is, and nothing in
  the walk looks at who pushed.
- That a forge's "merged" mark after a plain push to `main` holds on both forges, or the pull request is closed with
  a comment instead.
- A private organization repository: the person must be able to read it to fork; one who cannot is told so, and no
  other way in is built.
