# B711 — A Signers File Read As Bytes, As ssh-keygen Reads It

```json
{
  "$schema": "issue-1.json",
  "id": "B711",
  "title": "A Signers File Read As Bytes, As ssh-keygen Reads It",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 65,
  "blockedBy": [],
  "covers": "An allowed-signers file that is not valid UTF-8 is read line by line as ssh-keygen reads its bytes, and is not refused whole.",
  "openQuestions": 0
}
```

**Why this size.** One reader and its test.

Filed 2026-11-13 from building the reading of signers files as git reads them. Porelo now reads a signers file as git and ssh-keygen read it,
line by line, but it still decodes the whole file as UTF-8 first. One line with a byte that is not UTF-8 refuses the
whole file, while ssh-keygen reads the raw bytes, skips or reads that line, and verifies by the others. A commit that
git accepts is refused here. Failing closed is safe, but it is wrong.

**What must be true.** A line that is not UTF-8 is judged as ssh-keygen judges it, and the other lines still count.

## Plan

- Split the file into lines at `\n` as bytes, and decode each line on its own. A line that does not decode is put to
  ssh-keygen as bytes and named nobody when ssh-keygen cannot parse it, the same as any other line it cannot parse.
- Red first: a file with one Latin-1 line and one valid signer verifies a commit by the valid signer, as `git
  verify-commit` does.

## Acceptance

- The test holds and fails without the change.
