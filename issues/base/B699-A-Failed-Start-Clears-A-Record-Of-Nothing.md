# B699 — A Failed Start Clears A Record Of Nothing

```json
{
  "$schema": "issue-1.json",
  "id": "B699",
  "title": "A Failed Start Clears A Record Of Nothing",
  "state": "Review",
  "size": "S",
  "priority": "Low",
  "rank": 53,
  "blockedBy": ["B384"],
  "covers": "A failed start that made no container of its own, finding the name's record pointing at a container that is gone, removes that record instead of leaving it behind.",
  "openQuestions": 0
}
```

**Why this size.** One branch in the cleanup [B384](README.md#b384) changed, and a test.

Filed 2026-11-13 from [B384](README.md#b384): a failed start now cleans up only the id its own create recorded, and
touches nothing when the record on disk names a live container. When that container is gone, the stale record stays.

**What must be true.** The record of a container that no longer exists is removed by the failed start's cleanup; one
naming a live container is still left alone.

## Plan

- `TaskLaunch`'s cleanup: with no own id, read the record's container; when podman knows no such container, forget the
  record through `TaskReservations`.
- Red first: a failed start with a stale record leaves none; one with a live container's record still leaves it.

## Acceptance

- Both cases hold; a test fails while the stale record stays.

## Built

- `TaskReservations.forgetGone(container, id)` (app-base, new): under the name lock, it removes both copies of the
  recorded id (runtime and durable), each only while it still names `id`. It acts only when podman answers "no such
  container" for the id and its task listing shows no row with it, and never when the name's claim holds that id as
  the container its start made.
- `TaskLaunch.cleanUpRecorded`: a failed start of its own with no id of its own, whose name's record names another
  id, calls it first. A record of a live container is still left alone, and the cleanup then goes on as before.
- Podman is asked by the id and in its listing because one answer alone is not enough: the fake runtime of the
  existing tests answers "no such container" for a container its listing shows up, and a real one may do the same in
  the moment between them.
- Test `TaskRunCommandTest.aFailedStartForgetsTheNamesRecordOfAContainerThatIsGone`: it failed on the build box before
  the change, with the gone container's id still recorded (`buildbox-porelo-8c1edfd3703e-20261113T183209Z.log`). It
  passes after the change, and `aStartThatLosesTheNameToAnotherStartStopsNothingOfThatOne` ([B384](README.md#b384)),
  the live container's case, still passes unchanged (`buildbox-porelo-80879843567e-20261113T183417Z.log`). The
  targeted run was 440 tests across `TaskRunCommandTest`, `TaskReservationsTest`, `StartCredentialEffectsTest`,
  `StartProgressReviewTest`, `StartStepsTest`, `TaskRunLimitTest`, `CredentialOfferTest`, `UpstreamProbeTest` and
  `HostKeyOfferTest`, with 0 skipped.
