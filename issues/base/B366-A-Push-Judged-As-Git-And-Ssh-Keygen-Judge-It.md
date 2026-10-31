# B366 — A Push Judged As Git And Ssh-Keygen Judge It

**Status:** Idea.
**Size:** S, proposed - five small corrections in the readers `porelo commit verify` shares with the gate, each with
its test.

**What must be true.** `porelo commit verify` ([B363](README.md#b363)) accepts a commit only where `git` and
`ssh-keygen` would accept it too, and refuses none they accept. A second reading of [B363](README.md#b363) on 2026-10-31
found five places where Porelo's own reading of a line or a commit differs from theirs:

- An allowed-signers option in quotes may hold `\"`, as OpenSSH reads it; `AllowedSigner` ends the quote there, so a
  `namespaces="git,a\",!git,\""` line is read as admitting git, which `ssh-keygen -Y verify` refuses, and a correct
  line holding `\"` is refused as unreadable.
- A `valid-after` time may end in `z`, `UTC` or `utc` as well as `Z`; `HostArrivals` takes only `Z`, so such a
  certifier is dropped and the task's commits are refused.
- Git leaves every `gpgsig` and `gpgsig-sha256` header out of what a commit's signature covers; `SignedArrivals`
  leaves out only the first, so a commit signed over a second such header is accepted where `git verify-commit`
  refuses it, and a commit signed for both object formats is refused.
- A reason or text cut at 16 KiB can split a character outside the Basic Multilingual Plane, leaving half of it in the
  JSON answer.
- `AllowedSigner.read` with a scratch directory leaves its check file behind when writing it fails.

Each is answered by a test that fails today; nothing else of the verb changes.
