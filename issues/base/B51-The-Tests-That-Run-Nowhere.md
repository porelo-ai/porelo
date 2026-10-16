# B51 — The tests that run nowhere

**Status:** Estimated.
**Size:** M - a JUnit condition, one test class run on the fedora leg, and a report across legs that touches the leg
driver and the workflow.

**What must be true.** Whoever reads a green build can tell a test that did not apply on this run from one that
cannot run anywhere in the pipeline, and the SELinux-dependent unit tests run where SELinux is.

## Why

Two unit tests in `SocketContextTest` are gated on `@EnabledIf("selinux")`, which asks whether
`/sys/fs/selinux` exists. They have never run.

### What was measured

During the integration tiers for a credential-scanner change:

- the ubuntu leg reported `Tests run: 51 … Skipped: 2`;
- the fedora leg reported **the same two skipped**, although the machine it rents has SELinux and
  the leg installs this product's own SELinux policy on it.

The reason is not the distribution. The leg builds the product on the rented machine with
`-DskipTests`; the unit tests in that log run on the **host driving the leg**, and that host has no
`/sys/fs/selinux`. So changing the target distribution cannot make them run: they are not executing
on the target at all.

### Why this is the shape that matters rather than two tests

A green build reported them as *skipped*, which reads exactly like a condition that did not apply
today. It is not: it is a condition that **cannot** apply anywhere in the pipeline as it stands.

This is the second instance of that shape here. The first is already in `AGENTS.md`: eleven `@slow`
acceptance scenarios were excluded everywhere, reported as skipped in every green build, and when
they were finally run they turned up three faults — one scenario asserting behaviour that had been
cut, one that could not run twice on a machine, and eleven that silently assumed exactly one agent
was installed. None of that was visible while the skip looked conditional.

### What it is not

Not a defect in `SocketContext`, and not evidence that the SELinux path is broken — it is evidence
that nothing here has ever checked it. The acceptance scenarios do exercise a container with the
policy loaded on the fedora leg, so the mechanism is not untested end to end; what is untested is
the unit behaviour these two cases were written for.

## The shape

The SELinux-dependent tests have to execute where SELinux is. **The fedora leg runs those unit tests,
and only those, on the rented machine**; every other unit test stays on the build host, where it
runs today. A leg stays what it is - a deployment and the acceptance suite against it - with one
narrow addition for the tests that need the machine's kernel.

Two smaller things belong with it:

- **A skip that can never apply should say so**, rather than presenting itself as a condition that
  happened not to hold. A test disabled because no machine in the pipeline can satisfy it is a
  different fact from one skipped on this run.
- **The build should be able to report which conditional tests were satisfied nowhere**, across the
  legs of a matrix. One leg cannot see this; the pair of them makes it obvious.

## Plan

Read in today's code. `SocketContextTest` (`wire`) gates two tests on `@EnabledIf("selinux")`, which reads
`/sys/fs/selinux/enforce`. `acceptance/legs/src/main/java/org/fuin/porelo/legs/Main.java` builds with `-DskipTests`
before deploying, so the unit tests in a leg's log run on the host that drives it. Other tests skip through
`assumeTrue` (`KernelKeyringTest`, `VarlinkInteropTest`) and are of the same kind.

Steps, in order:

1. Red first: a test asserting that a test disabled for want of SELinux gives a reason naming where in the pipeline
   it runs. Then a JUnit condition in `testing` (`porelo-testing`) that takes the place of `@EnabledIf` and says
   "runs on the fedora leg" rather than an ordinary skip.
2. The two tests run on the rented machine: after deploying, the fedora leg runs the SELinux-dependent unit tests -
   today `SocketContextTest`, found by the condition of step 1 rather than by a list - in the checkout its snapshot
   already compiles, and fails when they are skipped there. No other unit test runs on the leg. Breaking the SELinux
   branch of `SocketContext` then turns that leg red.
3. Each leg uploads its surefire reports. A job after the matrix in `build.yml` lists the test cases skipped on
   every leg and fails naming them, unless a test says where it runs.

## Acceptance

- The two SELinux-gated tests in `SocketContextTest` execute on a machine with SELinux, and the fedora leg's
  report shows them run, not skipped. Seen to fail: breaking the SELinux branch of `SocketContext` turns that leg
  red.
- The fedora leg runs no unit test that does not need SELinux; the rest run on the build host as before. Seen to
  fail: the leg's report lists a test class without the SELinux condition.
- A test whose condition no machine in the pipeline can satisfy reports itself as such, not as skipped on this
  run. Seen to fail: a test gated on a condition no leg meets shows up in the build as an ordinary skip.
- The build reports, across the legs of a matrix, which conditional tests were satisfied nowhere. Seen to fail: a
  matrix in which both legs skip the same conditional test passes without naming it.

## To be checked

- **Where the report across legs lives** (Agent Core's): in this repository's workflow, or as a command of
  `porelo-release` that the agent repositories could run too.
