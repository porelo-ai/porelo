# B339 — The Registry Read And Written By Its Signers

**Status:** Accepted.
**Size:** M - estimated 90 minutes from the median of M builds: the per-path rule beside the protected-file rule, the
registry verbs, the forge's key list at first read, docs.

**What must be true.** An organisation's registry repository on the central forge holds `owners.yml` - the registry's
owners and the host admins, each by login and key, and an offline backup key where a single owner or host admin needs
one ([B346](README.md#b346)) -, `people/<login>.card`, `hosts/<host>/card` and `hosts/<host>/access.yml`. Write access
to it is wide, so a commit's signer alone proves nothing: Porelo checks every commit along the first parent since the
state it trusted last, path by path, and every new commit against the revocation list as the follow does
([B341](README.md#b341)).

- A card is changed only by its own key, and a change of key also by its predecessor. A card replacing a lost or revoked
  key is accepted only when a registry owner signs it.
- `owners.yml` is changed only by a registry owner it names, and the first one is accepted only when its signer is an
  owner of the forge organisation whose key the forge lists for their account.
- A host's card and access file are changed only by a host admin `owners.yml` names for that host; the card itself is
  also checked as a host card ([B349](README.md#b349)). A host's access file names each project admin by login and key
  fingerprint, so the host can check them without the forge.
- Any other path is ignored and never read. A commit that breaks the rule keeps the last good state and is named with
  its path.

A card filed for a login is trusted at first read only when its key is among the keys the central forge lists for that
login: its authentication keys, and on GitHub its signing keys as well. Both lists answer without a token. Before the
forge was asked, as when the person is offline, the card is `first-seen`; on a forge without a public key list it stays
so until an admin confirms its fingerprint out of band.

`porelo registry read <checkout> --json` answers `{"people": [{"login", "name", "email", "key", "fingerprint", "state":
"trusted|first-seen|refused", "why"}], "owners": [{"login", "fingerprint", "backup"}], "warnings": [], "hosts":
[{"host", "card", "admins", "projects": [{"name", "admin", "access_repository"}]}]}`. `porelo registry file
card|host-card|host-access ...` writes and signs the commit with the person's key, by plain Git, never through a forge's
contents API.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- A card changed by another key than its own, or a host's access file changed by another key than a host admin
  `owners.yml` names for it, keeps the last good state and names commit and path.
- A card replacing a revoked key is accepted signed by a registry owner and refused signed by anybody else.
- An `owners.yml` whose signer is not an owner of the forge organisation is refused at first read.
- A card whose key the forge does not list for its login is `refused` with why; one the forge lists is `trusted`; one
  not yet checked is `first-seen`.
- `registry file` makes a signed commit that `registry read` answers as trusted.
- Seen to fail first: there is no registry reader today.
