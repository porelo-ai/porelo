# B366 — A Push Judged As Git And Ssh-Keygen Judge It

**Status:** Review.
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

## Done

- All five were measured first with OpenSSH 10.2 and git 2.53 and are real: `ssh-keygen -Y verify -n git` refuses a line
  with `namespaces="git,a\",!git,\""` and accepts one with `namespaces="x\" y,git"`; it accepts a `valid-after` ending
  in `z`, `UTC`, `utc` or `Utc` after 8, 12 or 14 digits and refuses `GMT` or a space; `git verify-commit` refuses a
  commit whose signature covers a `gpgsig-sha256` header and accepts one carrying both headers over the commit without
  either, and a second `gpgsig` header after a good one changes nothing it decides; a JVM may create the check file but
  fail to write it (`EFBIG` under `ulimit -f 0`).
- `AllowedSigner` finds the end of the options as ssh-keygen does, a `\"` neither opening nor closing a quote, splits
  them at commas outside quotes alike, and `HostArrivals` reads a quoted value with each `\"` as a quote. No
  `namespaces` or time value can decide differently for `git` by that last step, since a pattern holding `\` or `"`
  never matches `git`, so no test sees it.
- A `valid-after` time may end in `Z` or `UTC` in any case, after 8, 12 or 14 digits.
- `SignedArrivals.Signed` leaves every `gpgsig` and `gpgsig-sha256` header, with its continuation lines, out of what the
  signature covers, and takes the first header of the repository's format as the signature; the gate's arrival check
  reads commits through it too.
- Every text of the answer and the one line `porelo: <reason>` are cut at 16 KiB through one method, never between the
  two halves of a character.
- `AllowedSigner.read` with a scratch directory removes its check file when writing it fails.
- Each has a test seen red on the code before and seen red again by a mutant: in `CommitVerifyCommandTest` a certifier
  line negating `git` behind an escaped quote, one quoting an escaped quote and a space, `valid-after` suffixes, a
  commit signed over a `gpgsig-sha256` header and one signed for both formats, and a long reason ending in a character
  outside the Basic Multilingual Plane; the gate's `SignedArrivalsTest` splitting a commit with both headers and a
  mergetag; `AllowedSignerTest` reading in a JVM of its own that may create files but not write into them.
- `doc/commands.md` and `doc/hosts.md` say the lines and the commit are read as ssh-keygen and git read them.
- After a reading of the first build, three more places were measured with git 2.53 and OpenSSH 10.2 and are real:
  `git verify-commit` leaves out every header whose name starts with `gpgsig` - `gpgsig` alone with a continuation
  line, `gpgsig-other x`, `gpgsigfoo y`, `gpgsig<tab>x` - before or after the signature, refusing a signature over one
  and accepting one beside it; ssh-keygen reads a principal list from a first quote to the next, whether or not a space
  follows it (`"Alice Smith"`, `Al"ice Smith"` and `t@x,"other name"` are read, `"a b` and `a"b c"d` are not); and it
  takes a `valid-after` day up to 31 and a second up to 61, rolling over as `timegm` does (`20210229` from March 1,
  `20200101235960Z` from January 2, `20200101235961Z` a second later), refusing day 0 or 32, month 0 or 13, hour 24,
  minute 60, second 62 and any time not after 1970 began. `SignedArrivals.Signed` now leaves out every such header,
  `AllowedSigner` finds the principal list's end as ssh-keygen does and keeps it without its quotes, the certifier lines
  handed to ssh-keygen quote it, and `HostArrivals` reads a `valid-after` time with ssh-keygen's ranges and roll-over.
  Each has a test in `CommitVerifyCommandTest` or `SignedArrivalsTest`, the commits also judged by `git verify-commit`
  in the test, seen red on the code before and red again by a mutant.
