# [B484](README.md#b484) — A Host Places Only What It Carries

**Status:** Idea. Filed on the operator's word (2026-11-06), from today's measurement: the default project on a host, and
how a host handles forges, are designed with the operator first.

**What must be true.** On a host, only what the host carries works through its own forge: a project carried whole, and
of a project carried in part the repositories its `host.yml` entry names. Everything else works against the
repository's central address, as on a machine that is no host, without a refusal: a project the host does not carry,
the default project among them, and a repository of a partly carried project that is not named.

## Found (measured 2026-11-06, `HostForgeAddressTest`)

- With the host's `forge.conf` present, `HostForgeAddress.placed` points every row that names `central` at
  `http://127.0.0.1:<port>/<project>/<name>.git`, whether or not the host carries the project.
  (`aRowWithACentralIsReachedOnTheHostsForge`).
- A row without `central` keeps its address, and `HostForgeAddress.refusal` refuses a task on it: "names no central
  address ... Add 'central:'" (`aRowWithNoCentralKeepsItsAddressAndATaskThereIsRefused`). StartCheck and TaskLaunch
  call it on every start.
- The default project writes its rows with no `central` (`DefaultProject.fileHeld`), so a default-project task on a
  host is refused before it starts and pushes nowhere.
- What a host carries is known since [B479](README.md#b479): `OrganizationProjects.Placed.repositories`, from the host's
  own file (`[]` is the whole project).

## Open questions

- A default-project task on a host: should it go to its central address without a refusal, and what holds it end to end
  (through DefaultProject and StartCheck)?
- A project with `central` rows that the host does not carry: should it stay at its central address rather than the
  host's forge?
- A partly carried project: should the repositories its `host.yml` entry names go through the host's forge and the
  rest to their central address?
- Whether a task on a repository that is not carried, on a host, needs anything the host's gate gives a carried one
  (its review, its build reader) or simply works as on a machine that is no host.
