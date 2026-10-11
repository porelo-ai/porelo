# B10 — What An Egress Set Can Express

**Status:** Analyzed.
**Size:** L - four separate pieces in core, shield, the task's wiring and the daemon, and `Sets()` grows two fields.

**What must be true.** An operator who needs a destination that cannot be written as a host name
gets it supported or refused with a reason, never silently unreachable, and can tell when two
machines' sets of the same name differ or when a set cannot be complete.

## Why

A set is a name for a list of host names, resolved by dnsmasq and turned into firewall elements as
each answer arrives. That shape is what makes a set reviewable - a file of names anybody can read -
and it is also the limit of what a set can say. Four things do not fit inside it. Each was a
separate decision, and each was taken to keep that shape rather than to widen it: an address is
not a set's business, an undeclared name still does not resolve, a set is described rather than
versioned, and one that cannot be complete says so instead of pretending.

The four questions this requirement was written to ask are settled and recorded below with what
each of them costs; what is left is the work, and one smaller question that only appeared once the
others were answered.

## The shape

Decided: four questions, four answers, each with the thing it gives up.

### An address is a project's business, not a set's

**A project may declare addresses and networks; a curated set may not.** `project.yml` gains
`egress.addresses` beside `egress.sets` and `egress.domains`. A set stays a list of names.

The line is between what is shared and what is local. A set is a file people pass around and
review, and the same address means different things on two networks - `10.0.0.7` is somebody's
registry here and somebody else's printer there. A name does not have that problem, which is why
sets are made of names. An operator's own machine-local grant belongs in their own project file,
where it is read by whoever reviews that file and nobody else.

What it costs: an address is not reviewable the way a name is. Nobody reads `10.0.0.0/24` and
knows what was granted, and the firewall cannot tell them. That is accepted, and it is why the
grant is visible in the project file rather than hidden in a set.

The only refusal is the default route - `0.0.0.0/0` and `::/0`. A grant of everything is not a
declaration, it is the feature turned off, and it must not be expressible as a line that reads
like configuration.

Mechanically small: `NftRuleset` already allows an address *or a network* on the permitted ports,
because that is how a cleared destination is added. What is missing is the field, its validation
and the wiring.

### An undeclared name stays NXDOMAIN

**Refused lookups become visible instead.** Resolving an undeclared name and turning it into a
clearance question was rejected: it tells the container that a host exists, hands it an address it
can use later without asking DNS again, and turns every stray telemetry lookup into a question for
a person.

The thing that made it attractive was legibility - "blocked" reads better than "does not exist" -
and that is obtainable without any of the cost. dnsmasq is already configured with `log-queries`
and writes every question the container asked, refused ones included. The names are on disk; they
are simply never shown. Showing them is the whole of this decision.

### A set is fingerprinted, not versioned

**Each set carries a checksum of its contents, reported wherever sets are listed.** Pinning -
`maven@2` in a project file - was rejected: it creates a distribution problem the moment it works
(who serves version 2, what happens on a machine that has only version 1, how does a correction
reach a pinned set) and none of that is a problem anybody has yet.

The problem people do have is diagnostic: a task works here and fails there, and the explanation
is a file of the same name with different contents, because an operator's own file wins over the
packaged one. A fingerprint in `sokar shield sets`, in `Sets()` and beside a task's egress report
answers that in one line without pretending to solve distribution.

### A set that cannot be complete says so

**The set format gains `complete: false` and a line saying why**, carried through `Sets()` so an
interface can show it rather than presenting such a set as equivalent to the others. Plus the way
out: an image snippet pinning a `baseurl`, which is what actually fixes it.

`os-packages-fedora` is the case. `dnf` resolves mirrors from a mirrorlist that differs by region
and by day, so hosts beyond the declared list arrive as clearance prompts, and no list of names can
ever be finished. Marking it is honesty; the snippet is the fix, and the two belong together -
telling somebody their set is incomplete without telling them what to do about it is a worse
outcome than saying nothing.

## Notes

The set contents were adapted from the project acknowledged in the
[README](../../README.md), except `maven`, which was measured here: 193 artifacts resolved into an
empty local repository through a logging proxy, which saw `repo.maven.apache.org` and, for
snapshots, `central.sonatype.com`.

## Plan

Read in the code at `30c8169b`. The four decisions are independent and are built as four commits, in this order.

**1. Addresses in a project.** `Egress` (`core/.../project/Egress.java`) is `(sets, domains, refused)`;
`ProjectSchema` lists `egress` as `sets`, `domains`, `refused`, and `ProjectReader` reads them at `egress.get(...)`.
`NftRuleset.allowV4` and `allowV6` (`shield/.../NftRuleset.java`) already take an address or a network on the
allowed ports.

- `Egress` gains `addresses`; `ProjectSchema` gains the key with its description; `ProjectReader` validates each
  as an IPv4 or IPv6 address or CIDR and refuses `0.0.0.0/0` and `::/0` with the reason. A repository's addresses
  are added to the project's, as `Project` merges its sets and domains.
- `TaskRunner` (`apps/app-base/.../TaskRunner.java`), where the ruleset is built, calls `allowV4`/`allowV6` for
  each; `EgressControl.reachable` (`apps/app-egress/...`) and the `Egress` reply list them beside the hosts.
- Red first: `ProjectReaderTest` with `0.0.0.0/0` accepted; then an acceptance scenario in `.../shield/` that
  declares a network and reaches an address in it.

**2. Refused lookups shown.** dnsmasq already logs every query (`DnsPolicy`: `log-queries`, `log-facility=-`), and
the hook writes it to the task's `dnsmasq.log` (`hooks/.../SupervisorHook.java`, `LOG_FILE`). `ShieldWatchCommand`
already reads that log to name an address. A reader of it in `shield` (beside `ResolvedNames`) returns the names
answered NXDOMAIN; where they are shown is the question below. Red first: a test feeding a captured `dnsmasq.log`
with one refused query, which goes red when the name is missing.

**3. A fingerprint per set.** `EgressSet` (`shield/.../EgressSet.java`) is `(name, label, domains)`, read by
`EgressSetDirectory`. It gains `fingerprint`: sha256 over the sorted, lower-cased domains, so comments and order do
not change it. `Sets()` (`daemon/.../EgressMethods.java`) and `sokar shield sets` show it, and the egress report
(`EgressReport`) names it beside each set. Red first: two directories holding `maven` with different domains whose
fingerprints agree.

**4. A set that cannot be complete.** The set's YAML gains `complete: false` and `incomplete_reason`; `EgressSet`
carries both, `Sets()` replies with them, and `egress/os-packages-fedora.yaml` moves its comment into them. The way
out - a `baseurl` pinned in the project's `image.snippet` - goes into `doc/project-file.md` beside the egress keys.
Red first: the listing of `os-packages-fedora` without `complete: false`.

Contract: `Sets()` and `Egress` grow optional fields in `25-egress.varlink`, which only ever grows;
`InterfaceDescriptionTest` holds them to the registration. The interface reads them (sokar-frontend).

## Acceptance

- A destination that cannot be expressed as a host name is either supported or refused with a
  reason, rather than silently unreachable.
- Two machines naming the same set reach the same hosts, or can be told that they do not.
- Where a set cannot be complete, the interface says so rather than presenting it as equivalent to
  the others.
- **Seen to fail:** a test that declares `egress.addresses` with a network goes red when the task
  cannot reach it; one declaring `0.0.0.0/0` or `::/0` goes red when it is accepted; one where a
  task looks up an undeclared name goes red when the name resolves or the refused lookup is not
  shown; one with two sets of the same name and different contents goes red when their fingerprints
  agree; one listing `os-packages-fedora` goes red when `Sets()` does not carry `complete: false`
  and its reason.

## To be checked

- **Where refused lookups are shown** (Agent Core's, with Agent Frontend's view). They exist in the resolver's log,
  per task. `task list` is a table with no room for them, and the daemon's `Task` fields are settled (what `task
  status` says is in [Commands](../../doc/commands.md#task)) - so this is a question about a surface rather than
  about data. A method of its own, a field on `Task`, or a line in the egress report: the choice decides whether an
  operator sees them while a task runs or afterwards, and only the first is any use for the case that motivated it.
  `Tail` already serves the task's `dnsmasq.log`, which may be enough for the terminal.
