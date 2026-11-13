# B484 — Working On A Repository Outside The Organization's Projects

```json
{
  "$schema": "issue-1.json",
  "id": "B484",
  "title": "Working On A Repository Outside The Organization's Projects",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 9,
  "blockedBy": [],
  "covers": "How a repository of none of the organization's projects is worked on in the default project, alike on a laptop and on a host with or without its forge: first one of the person's own, pushed to; then someone else's, through a fork. Open question for the operator."
}
```

Reworded on the operator's word (2026-11-07) into an open question: how a repository that belongs to
none of the organization's projects is worked on in the default project, alike on a laptop and on a host with or
without its own forge.

**What must be true.** A person has Porelo set up - on a laptop, or on a host with or without its own forge - and
wants an agent to make a change to a repository that belongs to none of their organization's projects: in the simple
case a repository of their own that they push to, and as a further case someone else's, for instance a bug fix for an
open-source project. It goes into the default project, and the work reaches its place the same way everywhere: the
agent works on the repository's code, the person reviews and approves at the gate, and the approved work arrives where
the person can propose it to that repository's owners. A host does nothing different for it than a laptop does,
whether or not the host runs a forge of its own.

## The problem

- **Today, on a host with its forge on**, `HostForgeAddress.placed` points every row naming `central` at the host's own
  forge (`http://127.0.0.1:<port>/<project>/<name>.git`), whether or not the host carries the project. Since the default
  project writes `central:`, a default-project task on such a host is pointed at a repository the host's forge does not
  hold, because nothing carried it there. What then fails - the mirror's seeding or the forwarding after approval - was
  not measured. Until 2026-11-06 the same task was refused at its start, because the default project wrote no
  `central`.
- **The simple case comes first:** a repository of the person's own, outside every organization project, with one
  address the person pushes to. It must work on a laptop and on a host, with or without the host's forge, before any
  further case.
- **A further case, someone else's repository, is not pushed to.** A person cannot push to someone else's repository;
  a contribution goes to the person's own fork and is proposed from there as a pull request against the original. Such
  a repository has two addresses: where the work comes from (the original: the mirror is seeded from it, and it holds
  the base branch of the pull request) and where the work goes (the fork: the gate forwards the approved work there). A
  project file names one address per repository today, `central`.
- **Laptop and host must behave alike.** On a laptop there is never a local forge; on a host there may be one. Such a
  repository must not depend on which.

## Found (measured 2026-11-06, `HostForgeAddressTest`; read again in the code 2026-11-07)

- With the host's `forge.conf` present, `HostForgeAddress.placed` points every row that names `central` at
  `http://127.0.0.1:<port>/<project>/<name>.git`, whether or not the host carries the project
  (`aRowWithACentralIsReachedOnTheHostsForge`); `Repository.reachedAt` puts that address in the row's `upstream`, so
  the host's address replaces the one the row names as the mirror's source and the gate's target alike.
- A row without `central` keeps its address, and `HostForgeAddress.refusal` refuses a task on it: "names no central
  address ... Add 'central:'" (`aRowWithNoCentralKeepsItsAddressAndATaskThereIsRefused`). StartCheck and TaskLaunch
  call it on every start, and GateSupport too. `refusal` also refuses a start whose `--upstream` names another address
  than the host's forge, so a person cannot name the repository's own address by hand on such a host.
- The default project now writes each row's `central` equal to its `upstream` (`DefaultProject.fileHeld`), so a
  default-project task on a host is no longer refused at its start but pointed at the host's forge, which holds no copy
  of it.
- What a host carries is known since [B479](README.md#b479): `OrganizationProjects.Placed.repositories`, from the host's
  own file (`[]` is the whole project); `placed` does not ask it.

## Open questions

- For the further case only: one address or two for a repository whose work goes to a fork: `central` is the fork and
  the origin is named elsewhere, or a second key names where the work comes from?
- Who opens the pull request against the original: the person by hand, or Porelo through the forge clients once the
  approved work is at the fork?
- Whose credential forwards the approved work to the repository (or the fork) on a host, which keeps no lasting token:
  the person's own from the vault, held only for the forwarding, or none, so a host's default project works only for
  what needs no push?
- Does such a repository's task need anything a host's forge gives a carried one (its review, its build reader), or
  does it work as on a laptop?

## To be checked

- Measure the simple case on the VM before the design: the host's forge on and off, a default project with a
  repository of the person's own, a task started, approved and forwarded; each step's result, and the same on a
  machine that is no host.
