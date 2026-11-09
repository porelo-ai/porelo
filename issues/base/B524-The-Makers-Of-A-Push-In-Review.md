# [B524](README.md#b524) — The Makers Of A Push In Review

**Status:** Review. Filed 2026-11-08 for the window's review, which shows who made each commit of a push (move 3's finding 14).
**Size:** S - two Tasks1 fields filled from git.

**What must be true.** `PendingPush` carries its head commit's `author` and `committer` (`name <email>`) and
`trailers` (the message's trailer lines, as `git interpret-trailers --parse` reads them). `Review` carries a
`commits` list beside `log`, each with `commit`, `subject`, `author`, `committer` and `trailers`. The window shows
them and checks one Assisted-by and no Co-Authored-By itself; the daemon judges nothing.

## Why

`PendingPush` holds `commit` and `subject` only, and `Review`'s log is `%H %s`, so the window says "not reported by the
machine" where the reviewer needs to see who made a commit and with what.

## Acceptance

- Red first: a pending push of a commit with two trailers reports both, and its author and committer.
- Names and emails pass through as git gives them, control characters shown as the gate shows a subject.

## As built

- `GitGate.made(name, against)` and `madeHead(name)` read each commit's makers with one `git log`, its fields cut
  by the unit and record separators, so no name or trailer an agent writes ends a field. Trailers are
  `%(trailers:only,unfold)`. (Replaced after the reading, below.) A ref that is not a commit gets no makers, and is listed all the same.
- Tasks1: `PendingPush` gains `author`, `committer` and `trailers` (all optional). `Review` gains
  `commits: ?[]ReviewCommit` (`commit`, `subject`, `author`, `committer`, `trailers`), newest first, over the
  range `log` shows. A daemon from before leaves them absent.
- Tests: `GitGateTest.theReviewNamesEachCommitsAuthorCommitterAndTrailers` and
  `PoreloDaemonTest.aReviewAndAPendingPushNameEachCommitsMakersAndTrailers`, both seen red.
- Changed after the reading (the agent writes every byte of its commit):
  - No separator byte parses the agent's bytes any more: a `\x1f` or `\x1e` in a subject or name could show
    another author and an Assisted-by, or drop a commit. Makers come from `git log --format=%H%n%an <%ae>%n%cn
    <%ce>%n%s`, four lines a commit since an ident and a subject hold no newline, split on `\n` only; a count
    that is no multiple of four or an id line that is not a full commit id fails closed (`GateException` for
    Review, no makers for Pending).
  - Message and trailers each come from a `git log -z` (`%B`, `%(trailers:only,unfold)`), kept only when the
    NUL-separated records are exactly the commits of the first call, in order, each led by its id; else no
    message, no trailers. A third call rather than `interpret-trailers` per commit: three processes however
    long the push, and the trailers are git's reading of the whole message.
  - Capped so one commit cannot outgrow the 4 MiB a Pending reply carries: subject, author, committer and each
    trailer at 1000 code points, at most 50 trailers, the message at 16384 code points, never inside a pair.
  - `message`: on `Made`, `ReviewCommit` (`message: string`, `""` when unmatched) and `PendingPush`
    (`message: ?string`), so a Co-Authored-By outside git's trailer block is there to see.
  - `madeHead` names nobody unless `git cat-file -t` of the ref says `commit`: `git log` peels a tag. Pending
    puts the makers on a push only when that head is the push's `commit`, not one pushed in between.
  - Tests: `GitGateTest.separatorBytesInASubjectAndAnAuthorNameCannotForgeOrHide`,
    `anIncomingRefThatIsATagNamesNoMakers`, `aHugeSubjectNameAndTrailerBlockAreCapped`,
    `theMessageCarriesACoAuthoredByOutsideTheTrailers`, and the daemon test above for `message`, all seen red.
- Not built: control characters are passed as git gives them. The window escapes them as it does a subject, the
  same as `subject` today.

