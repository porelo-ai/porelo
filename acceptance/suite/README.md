# Acceptance Suite

This repository's own scenarios: what a person does at a terminal with Porelo, run against a real
machine through the [kit](../kit/README.md).

```
./mvnw -pl acceptance/suite verify \
    -Dporelo.acceptance.host=user@host \
    -Dporelo.acceptance.key=<your-key-file>
```

Without `porelo.acceptance.host` the module is skipped, so an ordinary build is unaffected. The
report lands in `acceptance/suite/target/acceptance.html`, and on GitHub as inline annotations and
a job summary - see the kit's README for both.

## What it covers

Every command a person types, and what a task is promised end to end: its image, its hardening, the
credential it never sees, the names and ports it may reach, the gate its work leaves by, and what a
refused start leaves behind. A scenario can drive a terminal - the offer to start a stopped task,
color on work that exists nowhere else, a passphrase read without being echoed all exist only
there - or assert the absence of one, which is half of every such behavior.

## Writing a scenario

Steps come from the kit; the two vocabularies - a person watching, a script nobody watches - are
described there. This module adds its fixture cleanup hooks and the steps of a task's runs at a pull
request (`pr-review.feature`, run alone with `-Dcucumber.filter.tags=@pr-review`): what the stub forge
adapter answers and what it wrote, what the stub agent submits in a run, and what the owner account
releases. Every write of the adapter's answers and every read of its write log goes through
`ForgeStub`, whose file formats are each one method; the pages it writes are the review profile's
fixtures in `builds/api`, read in place. What the stub agent does in its next run is the
`.porelo-stub-review` file `StubReview` makes, committed into the pull request's head. A pull request's
repository is served by `StubGitForge`: a loopback https server on the machine, its throwaway authority added
to the account's `ca-certificates.pem` while it runs, serving repositories by git's smart http to the
scenario's token alone, so a reviewer's start fetches the head the facts name.

**A run on a kept machine has one account, a leg has four.** `Leg.prepare` gives the accounts beside the build user a copy of
what the build user has installed, and only what it names. A scenario relying on something new in an account's install passes on one
account and can fail on the others, so add it there; a run on one account cannot show this, only a Build's legs can.

## What it costs

Each scenario talks to a real machine over ssh. A scenario that waits for something that never
comes takes the full patience of `Terminal` (30s) before it fails, so a wrong expectation is slow
rather than instant. Scenarios that need a task built are minutes, not seconds - which is the
reason to keep asking whether a case belongs here or in a unit test.
