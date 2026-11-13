# B649 — Signers Files And Signatures Read As Git Reads Them

```json
{
  "$schema": "issue-1.json",
  "id": "B649",
  "title": "Signers Files And Signatures Read As Git Reads Them",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 44,
  "blockedBy": [],
  "covers": "An allowed-signers file and a signature are read as ssh-keygen and git read them: a lone CR, a line it cannot parse, a Unicode space before a comment; no backup key certifies; a task signs only under its own certificate",
  "openQuestions": 0
}
```

Split out of the gathering issue for small edge cases on 2026-11-12 (ST56), by the operator's decision. Each point was found by a reading or a fix, and reaches no other person's key, card, socket, mailbox or container.

**What must be true.** Porelo accepts the signers files and signatures git accepts and refuses none git refuses, and only a project's certifiers certify a task or a message:

- Medium: Porelo reads a lone `\r` as a line end in an allowed-signers file, but ssh-keygen reads
  `alice\rssh-ed25519 K` as one line, so Porelo refuses a file git reads. Found while fixing the signers file reading.
- Low: a line ssh-keygen cannot parse is skipped by git, while Porelo refuses the whole signers file (66).
  `AllowedSigner` trims only spaces and tabs, so `U+2000#x ssh-ed25519 K` is not skipped as a comment.
  The whole-file refusal remains fail closed. Found while fixing the signers file reading.
- Low: task and message certifiers both come from `ProjectMembers.certifiers`; the earlier difference between
  `TaskCertifying` and `MessageSigners.certifiers` is gone. Check that a backup key cannot enter that list or
  certify either. Left when the task-key messages met the backup keys that are never pinned.
- Low: a task's own key is still used to sign when its certificate names another task.

## Plan

- `AllowedSigner` and its file reader in `organization/git-tree`: line ends as ssh-keygen splits them, every Unicode space trimmed before `#`, an unparseable line skipped as git skips it. The whole-file refusal stays only where git refuses too.
- `ProjectMembers.certifiers`: a test that a backup key never enters the list; `TaskSigning`: a key signs only when its certificate names this task.
- First red: `AllowedSignerTest` reads `alice\rssh-ed25519 K` as one line.

## Acceptance

- Each point above has its case seen red first, against the same input given to `ssh-keygen -Y verify` in the test.

## Built (2026-11-13)

Each case is held against the same input given to `ssh-keygen -Y verify` (and `-Y find-principals`) in its test, so the
test shows git's own reading; the keys are throwaway ones made in the test's directory.

- **A lone `\r`.** `AllowedSigner` lives in `apps/app-base`, not in `organization/git-tree`, which only writes a
  one-line signers file. A line now ends at a line feed alone; a carriage return is a blank where ssh-keygen ends the
  principal list and skips around a line, and nowhere else, as ssh-keygen splits the options and the key at spaces and
  tabs only. Red first: `AllowedSignerTest` refused `alice\rssh-ed25519 K` while ssh-keygen verified it.
- **A line ssh-keygen cannot parse** is skipped, as git skips it: a principal alone, an unknown option, a bad date, an
  unclosed quote, a key followed by `\r` and more. ssh-keygen judges each line (`-Y match-principals`, which stops at
  the first line it cannot parse, so the file is asked whole and, when that fails, line by line). The whole-file
  refusal stays for a line ssh-keygen parses and Porelo does not (fail closed) and for a file that cannot be read.
  Red first: `AllowedSignerTest` and `CommitVerifyCommandTest` refused the whole file (66) where git accepted.
- **A Unicode space before `#`.** ssh-keygen skips only spaces, tabs and line ends before a comment: it reads
  `U+2000#x ssh-ed25519 K` as a signer whose principal is `U+2000#x`, finds that principal for the key, and git
  verifies by it. So Porelo now reads it as ssh-keygen does, the opposite of trimming every Unicode space; the earlier
  reading skipped it as a comment and refused a commit git accepts. Red first: `AllowedSignerTest` read no signer.
- **`ProjectMembers.certifiers`.** Held already: a backup key filed for the owner who administers the project never
  enters the list, for tasks and for messages alike, and ssh-keygen traces no task signature certified by it.
  `ProjectTakingTest.a_backup_key_certifies_neither_a_task_nor_a_message` passed at once, so it is no red.
- **Signing.** The gate's `TaskSigner` (in `apps/app-gate`; `TaskSigning` only sets git's environment) signs only when
  the certificate names exactly this task's principal, as certification issues it; otherwise it signs nothing and the
  gate's log names both principals. Red first: `TaskSignerTest` signed under a certificate naming another task, whose
  signature ssh-keygen does not verify as this task's.
