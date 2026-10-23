# B265 — A Reserved Variable Refused With Its Fix

**Status:** Accepted. Small; built together with [B268](README.md#b268) and [B267](README.md#b267).

**What must be true.** When a repository's `porelo.yml` sets a variable Porelo sets in every task (`PATH`, `HOME`,
`LANG` or any `PORELO_*`), the refusal says what Porelo sets it to and what to do: remove the line.

## Why

Seen in the operator's test on a rented host on 2026-10-23: a task start in `porelo-message-matrix` was refused with
"porelo.yml at refs/heads/main of matrix: 'image.env.LANG' is set by Porelo and cannot be set here". The refusal was
right. Porelo writes `ENV LANG=C.UTF-8` into every task image, exactly the value the line set. But the words name no
value and no fix, so the person reading it, often not the repository's owner, could not tell that removing the line
changes nothing.

Read in the code: `RepositoryImageReader` refuses every name in `PORELO_OWNS` and every `PORELO_*` with the same
sentence.
