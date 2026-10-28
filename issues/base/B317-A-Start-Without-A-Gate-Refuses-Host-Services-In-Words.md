# B317 — A Start Without A Gate Refuses Host Services In Words

**Status:** Analyzed
**Size:** XS - one refusal in the start's checks and its test.

**What must be true.** `porelo task start --no-gate` for a project whose egress declares `host_services` is refused in
one sentence before anything is made, instead of failing with a `NullPointerException` while the image is built.

## Plan

Read in the code on 2026-10-28.

- **Seen:** on a rented host, `porelo task start talk-check --project sprint5 --repository sluice --agent claude
  --attach shell --detach --no-gate` passed the room step and stopped at the image with
  `java.lang.NullPointerException: declared host services require the gate's address`.
- **Why:** a declared host service is answered with the gate's address (`TaskRunner`, app-base, the
  `requireNonNull` before the ruleset and in the DNS policy), and `--no-gate` has no gate, so there is no address.
  The invariant is right; it is reached only because the start lets the combination through.
- **Step:** the start refuses `--no-gate` together with a non-empty `project.egressFor(repository).hostServices()`
  among its early refusals in `TaskLaunch`, naming the host services, exit 2, "nothing was created". It names no
  way out beyond starting with the gate, which is available in that state.
- **The test that goes red first:** a start with `--no-gate` of a project declaring a host service answers that
  refusal and creates nothing (red today: it reaches the image and throws). The harm: a start that fails late has
  already enrolled the task in the project's room and made its state.

## Acceptance

- The refusal in words, before the room step, with nothing created; a `--no-gate` start of a project without host
  services starts as before.
