# B142 — A Misuse Test Suite As A Release Condition

**Status:** Estimated.
**Size:** L - attack scenarios of three kinds and their steps in the acceptance kit, a documents test, and a
check in the release workflow.

**What must be true.** A release passes a suite of misuse scenarios - escaping the container, exfiltration past the firewall and the broker, bypassing the approval - before it is made.

## Why

The guarantees are tested one by one today; a suite that tries to break them as an attacker would is what a release should have to pass.

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §10; Acalvio 12. From a review of security guidelines, 2026-10-09.

## The shape

Scenarios in the acceptance suite, tagged, run by the release workflow; each names the guarantee it attacks and the page that states it.

## Plan

**Today.** The acceptance suite (`acceptance/suite`, features under
`src/test/resources/org/fuin/sokar/acceptance/`) checks guarantees one by one: `task-guarded.feature` (no privilege,
the firewall before the start, the phantom token, declared names only, work only through the gate) and
`task-forged.feature`. The release job `deploy` in `.github/workflows/build.yml` has `needs: [build, legs]`, so a red
scenario in a leg already holds a release back. Nothing groups the attacks, and nothing shows they ran: a tag filter
in `acceptance/legs/.../Leg.java` could leave them out and the release would still go.

**Steps:**

1. A tag `@misuse` and one feature per area (`misuse-container`, `misuse-egress`, `misuse-broker`, `misuse-gate`), all
   three kinds of attack in the first build, each scenario acting from inside the task as the agent would: escape (reach
   the host's container socket, write outside the workspace, gain a capability, read the host's processes); exfiltration
   (connect to the provider's host past the broker, resolve an undeclared name, carry data out in DNS names under a
   declared domain, send it to an allowed domain, send a credential header, ask the broker for a new token, push
   upstream from the container); bypassing the approval (call approve through the gate's port, move the ref after the
   review and expect `MovedSinceReview`).
2. Steps in `acceptance/kit` (`SokarSteps`, `Machine`) to run a command as the agent inside a task and expect a
   refusal by name.
3. Each scenario says the guarantee it attacks and the page that states it (`doc/security.md`, `doc/reach.md`).
   Red first: a test in `acceptance/suite`, tagged `documents`, that reads every `@misuse` scenario and fails when
   one names no page or a page that does not exist.
4. The legs report how many `@misuse` scenarios ran; the `deploy` job refuses a release whose legs ran fewer than
   the suite holds, none included.
5. **A found hole holds the release back.** A red `@misuse` scenario fails its leg, and `deploy` needs the legs, so
   no release is made until the hole is closed. A `@misuse` scenario is never skipped to let a release go: the
   documents test of step 3 also fails when a `@misuse` scenario carries a tag that a leg's filter leaves out
   (today `@restart`), and `Leg` never filters `@misuse` out.
6. `doc/reach.md` says that the suite exists, that a release waits for it, and that a found hole is closed before
   a release, never skipped.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- Every kind is covered: at least one `@misuse` scenario each for container escape, exfiltration (DNS, an allowed
  domain, the broker) and bypassing the approval.
- A release is refused when one `@misuse` scenario is red, and when fewer ran than the suite holds. Seen to fail: a
  leg that filters one `@misuse` scenario out and a release that goes all the same.
- The documentation page that describes the area says it.

## To be checked

- Whether misuse scenarios that need a forge or a restart run in the release legs, given that restarts are kept off
  the shared test machine (Agent Core's).
