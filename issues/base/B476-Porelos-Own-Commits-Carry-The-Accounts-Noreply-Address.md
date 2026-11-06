# [B476](README.md#b476) — Porelo's Own Commits Carry The Account's Noreply Address

**Status:** Idea. Low priority, on the operator's word (2026-11-06).
**Size:** S - the address from the forge's account answer, passed to the one place that writes the identity; tests.

**What must be true.** A commit Porelo writes itself (an organization write, `porelo project sign`, a created project
repository's first `project.yml`) carries the acting account's own noreply address on GitHub, of the form
`<id>+<login>@users.noreply.github.com` from the forge's answer for that account, instead of
`<login>@users.noreply.invalid`. GitHub then shows the commit Verified once the card key is also a signing key of that
account; today such a commit shows Unverified, because GitHub matches a signature to an account by the commit's
address.

## Notes

- The identity is written in one place: `TreeRepository` sets `GIT_AUTHOR_EMAIL` and `GIT_COMMITTER_EMAIL` to
  `<login>@users.noreply.invalid`.
- A forge with no such address in its account answer (Forgejo, for one) keeps `<login>@users.noreply.invalid`; no
  address is guessed.
- Nothing in Porelo's own checks depends on it: the reader judges a commit by its SSH signature's key against the
  cards, never by its author or committer address. It changes only what the forge's web page shows.
