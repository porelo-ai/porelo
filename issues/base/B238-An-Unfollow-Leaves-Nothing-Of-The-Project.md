# B238 — An Unfollow Leaves Nothing Of The Project

**Status:** Idea.

**What must be true.** `porelo project unfollow` removes what Porelo kept for building and fetching the project, so
`porelo prune` finds nothing of it afterwards.

## Why

Measured on `0.5.1~snapshot.307`: after `project unfollow` of three disposable projects, `porelo prune` listed each
project's `build/<project>.build.lock` and `upstream/<project>.<repository>` as left over, six entries, which had to
be removed by name.
