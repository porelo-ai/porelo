# stub

The build reader the tests read builds from, so they need no forge on the network; its forge is `stub-forge`, and it answers from an `answers.json` beside it. Only for tests and the acceptance suite.

`heads` in it maps a branch at the forge to its head commit. That is the branch the task's work reaches there - for an online task `porelo/<task>`, not the task's name.

It answers the review contract from the same file: `reviews` maps a pull request's address to its `facts` (with `approved`, and `decidedAt` for a merged or closed one) and its `pages`, `openPullRequests` maps a branch to the pull request open from it, and `reviewRefuse` refuses every review call. Each post is written as one line to `review-posts.jsonl` beside the answers, with the guid it is given then and without the token, and its result joins the last page of that conversation as a `forge-account` result: the stub's own account, or the one a pull request's `postedAs` names. A page past 100 results or 1 MiB spills onto pages of its own.
