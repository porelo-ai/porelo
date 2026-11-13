# B397 — A Plain Git Credential Goes To Any Host A Project Names

```json
{
  "$schema": "issue-1.json",
  "id": "B397",
  "title": "A Plain Git Credential Goes To Any Host A Project Names",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 35,
  "blockedBy": [],
  "covers": "A plain `git.token` or `git.ssh` reaches only a host the person meant it for, not whatever host a project's file names."
}
```

**What must be true.** A person's plain `git.token` or `git.ssh` reaches only a host the person meant it for. Today
`GitCredentialNames` falls back from the entry named for a host to the plain one, so a project's file, by naming a host, can
receive the person's plain credential; the fallback exists for a machine with one credential. A lent registration token
was bound to its server for this reason, with no plain fallback.

## To be checked

- `GitCredentialNames.hostOf` reads `https://evil.example?@github.com/x.git` as `github.com`, where git 2.53 connects to
  `evil.example`. Measured with `git credential fill` and a helper keyed `credential.https://github.com`: git does not run
  it for that address, so a helper keyed by host lends nothing there. Every other way a credential chosen by `hostOf` leaves
  is still to be read.
