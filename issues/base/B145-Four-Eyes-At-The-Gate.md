# B145 — Four Eyes At The Gate

**Status:** Analyzed.
**Size:** L - a node has one operator today, so a second person's approval needs a design first: a signature from
another person, made elsewhere and checked at the gate; touches task record, project file, gate, daemon contract.

**What must be true.** A project may require that the person who started a task does not approve its work alone: a second person approves.

## Why

Approval is one person today, who may be the one who asked the agent for the work.

**Guideline points it answers:** AISVS AC.4.1, AC.8.4; Swisscom 4.1 (roles). From a review of security guidelines, 2026-10-09.

## The shape

A project setting; who started a task is recorded; approve by the same principal is refused with who else may approve.

## Plan

**Today.** A node is one OS user (`doc/running.md`, "A Sokar node is an OS user"), and the glossary in
`doc/how-it-works.md` says the operator is the only one who can approve at its gate. So on one node the person who
started a task and the one who approves are always the same. `TaskProfile` (`wire/.../TaskProfile.java`) records the
agent, mode, provider and start time, not who started it. A signed approve (`GitGate.approveSigned`) already signs
with the person's own key through their git; a project's configuration commits are checked against its allowed
signers (`ProjectPaths.configurationSigners`).

**Proposed design.** A person is the principal of their signing key in the project's allowed signers - the same on
one machine and across machines.

1. `approval: {four_eyes: true}` in `project.yml`: `Project`, `ProjectReader`, `ProjectSchema`,
   `doc/project-file.md`, `DocumentedProjectFileTest`.
2. The task records who started it: `TaskProfile` version 5 with `startedBy`, the principal of the starting
   account's signing key.
3. A second approval is a signature over the reviewed commit by another principal of the project's signers
   (`ssh-keygen -Y sign`, a namespace of Sokar's own), made on that person's machine with a new
   `sokar gate co-sign`, and handed to the node; `Approve` (`35-gate.varlink`) and `gate approve` take it.
4. `GitGate` checks it with `ssh-keygen -Y verify` before anything is pushed; without one from a principal other
   than `startedBy`, the approve is refused and names the other signers.
5. `doc/how-it-works.md` (the gate) and `doc/corporate-security.md` say it.

Red first: a `GateApproveCommand` test in a four-eyes project - the starter's approve alone is refused naming the
other signers; with a second principal's signature over the reviewed commit, it forwards.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## Open questions

- Whether a node may need a second person at all, against "the operator is the only one who approves" (the
  operator's).

## To be checked

- How a person is identified on one machine and across machines; the proposal is the signing key's principal in the
  project's allowed signers (Agent Core's).
- How the second signature reaches the node: a file, a message between nodes, or the interface (Agent Core's).
