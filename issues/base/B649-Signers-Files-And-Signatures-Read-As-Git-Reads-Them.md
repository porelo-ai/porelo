# B649 — Signers Files And Signatures Read As Git Reads Them

```json
{
  "$schema": "issue-1.json",
  "id": "B649",
  "title": "Signers Files And Signatures Read As Git Reads Them",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
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
