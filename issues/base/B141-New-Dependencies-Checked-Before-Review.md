# B141 — New Dependencies Checked Before Review

**Status:** Analyzed.
**Size:** XL - manifests of five ecosystems, a registry check per ecosystem over the network and a contract change;
splits in three issues below.

**What must be true.** A dependency the agent's work adds is marked in the review and checked: that it exists in its registry, how old it is, and whether its name is close to a well-known one (slopsquatting).

## Why

Today the manifests are only shown first in a review; a hallucinated package name that someone registered is not told apart from a real one.

**Guideline points it answers:** OpenSSF 6; Checkmarx (slopsquatting). From a review of security guidelines, 2026-10-09.

## The shape

Per ecosystem the manifests are read (Maven, npm, PyPI, Cargo, Go); the check runs on the host, through the declared registries; the review shows each new name with its finding.

## Plan

**Today.** `ReviewRanking` (`gate/src/main/java/org/fuin/sokar/gate/ReviewRanking.java`) ranks a manifest or lockfile
`DANGEROUS` ("a dependency manifest or lockfile: it decides what code is fetched and run") and puts it first; nothing
reads what changed inside it. `GitGate.rankedReview` builds the review for `sokar gate review` (`GateReviewCommand`)
and for the daemon's `Review` (`daemon/.../GateMethods.java`, `35-gate.varlink`).

**Split into three, built in this order:**

1. **New names in the review (M).** `NewDependencies` in `gate` reads the old and the new content of each changed
   manifest from the mirror (`git show <base>:<path>`, `<commit>:<path>`) and names the coordinates the new one has
   and the old one has not: `pom.xml` (groupId:artifactId), `package.json`, `requirements*.txt` and `pyproject.toml`,
   `Cargo.toml`, `go.mod`. Lockfiles are left out at first: they name transitive dependencies nobody chose.
   `Review` gains `dependencies: []NewDependency` (ecosystem, name, version, file, finding) - a contract change;
   `gate review` prints them before the files. Red first: `NewDependenciesTest`, a pom that adds one dependency and
   keeps the rest - the review names that one and no other.
2. **The registry check (L).** Per ecosystem a reader of the registry's metadata answering: exists, first published
   on, not found, or not reached. It runs on the host at review, only against registries the project declares
   (`core/project/Egress.java`, its egress sets) - never one it does not; an `offline` project's review says
   "not checked: offline". Results are kept per name and version under the state directory. Red first: a stand-in
   registry answering 404 for one name; the review says "not in the registry" for it.
3. **Close to a known name (S).** A list of well-known names per ecosystem shipped with Sokar; a new name one or two
   edits away, or with a separator swapped, is named "close to <name>". Red first: `reqeusts` named close to
   `requests`.

**Docs:** the gate section of `doc/how-it-works.md` and the review row of `doc/reach.md`.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Which ecosystems and registries first; the proposal is Maven Central and npm (the operator's).
- Whether a finding only marks the review or also holds the approve back until a person confirms it (the operator's).
- Where the list of well-known names comes from and how it stays current (Agent Core's).
- How the interface shows a new dependency and its finding (Agent Frontend's).
