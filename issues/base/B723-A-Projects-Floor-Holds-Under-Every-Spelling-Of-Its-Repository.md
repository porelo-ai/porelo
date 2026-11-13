# B723 — A Project's Floor Holds Under Every Spelling Of Its Repository

```json
{
  "$schema": "issue-1.json",
  "id": "B723",
  "title": "A Project's Floor Holds Under Every Spelling Of Its Repository",
  "state": "Backlog",
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
