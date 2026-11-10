# B475 — A Refused Push Names What The Forge Answered

```json
{
  "$schema": "issue-1.json",
  "id": "B475",
  "title": "A Refused Push Names What The Forge Answered",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 12,
  "blockedBy": [],
  "covers": "A push Porelo makes that the forge refuses says the forge's own words, secrets withheld, as a refusal rather than \"cannot be reached, may have been made\"."
}
```

**Why this size.** one push result that keeps git's answer, its secrets kept out, carried to the refusal each writer
already gives; tests.

**What must be true.** When Porelo's own push to a project or organization repository is refused, the person reads
why: the forge's own words (a read-only key, a token without write, a protected branch, a non-fast-forward), with
every token, password, URL user part and key path withheld. Today `TreeRepository.push` runs `git push -q` and keeps
only whether it succeeded, so `porelo project sign` says "the organization cannot be reached, and the change may
have been made" for a token that may only read: the same words as a forge that did not answer.

## Found

On 2026-11-06, signing a project file anew over an https address with a token that had Contents read only: the
fetch read the public repository, the push was refused, and neither the answer nor `--json` said why. Finding the
cause took reading the code.

- `TreeRepository.push` returns a boolean; git's standard error is dropped.
- Its callers (`ProjectFile.sign`, the first `project.yml` of a created project repository, the organization's own
  writes) turn `false` into `GitTreeStore.Unreachable`, which the commands answer as "cannot be reached ... may have
  been made".

## Proposed

- `push` answers a result with git's last lines of standard error, cut to a few hundred bytes, after the same
  withholding `ImageSettings` uses for a URL's user part, and with any value of the lease's environment removed.
- A refusal git states (`[remote rejected]`, `! [rejected]`, `marked as read only`, `Permission ... denied`,
  `403`) is a refusal, not unreachable: "the forge refused the push: <its words>", exit 65, and the change was not
  made. Only no answer stays "may have been made".
- `--json` carries the words as `forge_said`.
- Red tests: a push to a bare repository with a hook that refuses names the hook's words; a refusal whose words hold
  a token shows it withheld; a remote that does not answer still says it may have been made.
