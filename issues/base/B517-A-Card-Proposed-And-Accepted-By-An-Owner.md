# [B517](README.md#b517) — A Card Proposed, And Accepted By An Owner

**Status:** Backlog, since 2026-11-09, in ST295. Filed 2026-11-08. Its plan reading comes first.
**Size:** M - a proposal's place in the organization's tree, a reading of waiting proposals, an owner's acceptance, and
the CLI for both sides.

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

Not planned yet. To settle in the plan:
- where a proposal is kept, so that a person without write access can reach it (a forge's pull request, or a place of
  its own);
- how an owner's acceptance is signed, and how a proposal is turned down or withdrawn;
- the readings the window needs, so porelo-frontend's side (blocked by this issue) can draw the waiting person's row.

A plan reading comes first: it is a trust boundary.
