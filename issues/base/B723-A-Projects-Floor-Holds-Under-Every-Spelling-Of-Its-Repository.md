# B723 — A Project's Floor Holds Under Every Spelling Of Its Repository

```json
{
  "$schema": "issue-1.json",
  "id": "B723",
  "title": "A Project's Floor Holds Under Every Spelling Of Its Repository",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 53,
  "blockedBy": [],
  "covers": "The date a machine last accepted a project's project.yml is found under every spelling a forge takes for that repository and under every key an older Porelo kept it under, two repositories never share it, and its check and its write are one step."
}
```

**Why this size.** One key function for the floor with its rules per form, the upgrade's old keys, the floor's check and
write under one lock, and tests for each spelling; all in `ProjectFile`, but each rule needs its own case.

Filed 2026-11-13 from the Sonnet reading of the fix for [B675](README.md#b675)'s reading (smith/b675-reds e98d136fd).
That reading found no Red and these five Yellows.

**What must be true.** A machine that accepted a project's `project.yml` refuses an older one however the filed address
is spelled and however the organization reaches it. Today:

- **Y1, a respelling finds no floor.** Only the host is put in lower case. A path in another case, and the forms
  `HTTPS://`, `http://`, `https://user@host` and `ssh://other@host`, each find no floor kept under another spelling.
- **Y2, the upgrade misses some old floors.** 0.5.0 kept the floor under the address as filed. It is found again
  when the ssh address carries the filed path and host as they were, but not when the filed address had no `.git` and
  the ssh one has it, had a trailing `/` or `:443`, or spelled its host in mixed case otherwise than the organization.
- **Y3, two repositories can share a floor.** ssh ports are dropped, so `:2222` and `:2223` share one, and so do `p.git`
  and `p` where a forge keeps both.
- **Y4, a lookalike host gets a floor of its own.** That only refuses, but it is not the same repository either.
- **Y5, check and write are apart.** The floor is read, the file judged, and the floor written after; two reads at
  once can each pass the check. This was so before.

Y1 and Y2 are the ones someone who can change the filed spelling could use to bring back an older signed
`project.yml`.

## Acceptance

- Red first, in `ProjectFileTest`: each spelling of Y1 finds the floor kept under the plain https form; each old key of
  Y2 is found from the ssh address; two ports of one host do not share one; two reads at once cannot
  both accept a file older than the one the first accepted.
- Not built tonight.

## Estimate

M, est=53 (p80 122) by the median velocity. There are no open questions.

## Built

All of it is in `ProjectFile` (organization/git-tree).

- **Y1, one form for every spelling.** A repository's address becomes one key, whatever spelling it is given in:
  - any scheme in any case (http, https, ssh, git+ssh), or `user@host:path`
  - with the user dropped, and a default port (443, 80 or 22) taken as no port
  - with the host and path lowercased, and a trailing `/` or `.git` dropped

  All of these become `https://host/path`.
- **Y2, older keys.** `keys()` also reads the keys an older Porelo kept the floor under. That is the one form as it
  was until now, and for an ssh address the filed https spellings it could have been given: the host as spelled and
  lowercased, with and without `:443`, and the path bare, with `/`, with `.git` and with `.git/`. They are read and
  lowered, never written anew.
- **Y3, ports.** A non-default ssh or http(s) port stays in the key, so `:2222` and `:2223` keep two floors.
  `p.git` and `p` still share one, since `.git` is a respelling by Y1, and sharing only refuses.
- **Y4, lookalike hosts.** A host is lowercased in A–Z only, so a lookalike such as the Kelvin sign stays its own host.
- **Y5, check and write in one step.** The early check stays unlocked, so a refusal makes no memory directory. After
  the file is parsed, the check is made again and the floor written under one lock: a lock within the process and a
  file lock on `floor.lock` across processes. A newer floor accepted meanwhile refuses the read as older.
- **A file signed anew lowers the floor under every key**, old ones included, so a later date left under an old key
  cannot keep it up.
- **Tests.** Six new tests in `ProjectFileTest`, one per point and one for signing anew. The Y5 test holds one read
  between its check and its write while a newer file is accepted, with no timing. One existing test pinned
  `ssh://…:2222` as the https form against Y3; it now pins `ssh://git@host/…` and `:22`. On the build box the new
  tests failed before the change (`buildbox-porelo-a82a53ed8230-20261113T173844Z.log`,
  `buildbox-porelo-9e68ea90727f-20261113T174445Z.log`) and pass after it
  (`buildbox-porelo-820a7ad27315-20261113T174556Z.log`: ProjectFileTest 61 and ProjectFileCheckoutTest 15, 0
  skipped).
- **Known limits:**
  - A floor that 0.5.0 filed under a host spelled in another case than the organization's (`https://Forge.Example/…`
    beside `git@forge.example:`) is not found from the ssh address. Finding it would mean passing the filed address
    into every reader, and the loss is accepted.
  - A floor written through `ssh://host:2222/…` is not found by a later https read of the same project, such as a
    start's fallback to the filed https address. The other order is found through the old keys.
