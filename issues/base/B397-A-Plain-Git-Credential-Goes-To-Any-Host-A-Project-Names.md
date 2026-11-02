# B397 — A Plain Git Credential Goes To Any Host A Project Names

**Status:** Idea.

**What must be true.** A person's plain `git.token` or `git.ssh` reaches only a host the person meant it for. Today
`GitCredentialNames` falls back from the entry named for a host to the plain one, so a project's file, by naming a host, can
receive the person's plain credential; the fallback exists for a machine with one credential. A lent registration token
was bound to its server for this reason, with no plain fallback.
