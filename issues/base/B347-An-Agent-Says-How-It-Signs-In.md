# B347 — An Agent Says How It Signs In

**Status:** Accepted.
**Size:** XS - no median of XS builds measured yet; under an hour: one field in the agent API and its check.

**What must be true.** An agent's definition says how it signs in: `login.flow` is `paste-code`, `device-code` or
`loopback`. In a session reached over SSH (`SSH_CONNECTION` set), where no browser redirect reaches the account, a
`loopback` flow is refused before it starts, naming the agent, and Porelo never sets `BROWSER`; the interface shows the
hint for the other two. On the person's own desktop a `loopback` flow runs as today.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- The three values are read; any other is refused naming the key.
- A `loopback` sign-in with `SSH_CONNECTION` set is refused before it starts, naming the agent; without it, it runs.
- A sign-in over SSH runs with `BROWSER` unset.
- Seen to fail first: today `login.flow` is unknown.
