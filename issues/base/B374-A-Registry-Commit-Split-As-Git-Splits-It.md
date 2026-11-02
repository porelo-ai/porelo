# B374 — A Registry Commit Split As Git Splits It

**Status:** Idea.
**Size:** S, proposed - two commit splitters of the vault area made to leave out every header starting with `gpgsig`,
as the host's own splitter already does, each with its test.

**What must be true.** Porelo checks a registry commit's signature over exactly the bytes git checks it over. Git
leaves out every header whose name starts with `gpgsig`, with its continuation lines, before it hands a commit to
ssh-keygen; the host's arrival check does the same. Two other splitters still leave out
only some of them: `RegistryCommit.parse` only `gpgsig `, and `IdentityChange.signedBy` only `gpgsig ` and
`gpgsig-sha256 `. A commit carrying, say, a `gpgsig-other` header is then judged by Porelo differently from git:
refused where git accepts it, or the signature read from the wrong header.

- Both splitters leave out every header starting with `gpgsig`, as git does.
- One splitting is shared, so a third place cannot drift from git again.

Found by the fix of a reading of the host's arrival check on 2026-10-31.
