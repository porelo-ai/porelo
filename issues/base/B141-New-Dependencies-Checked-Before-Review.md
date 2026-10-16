# B141 — New Dependencies Checked Before Review

**Status:** Estimated.
**Size:** XL - manifests of two ecosystems, a registry check each over the network, a hold on the approve and two
contract changes; splits in three issues below.

**What must be true.** A dependency the agent's work adds is marked in the review and checked: that it exists in its
registry, how old it is, and whether its name is close to a well-known one (slopsquatting). A finding holds the
approve back until a person confirms that dependency.

Maven Central and npm come first. PyPI, Cargo and Go follow later on the same reader and are out of scope here.

## Why

Today the manifests are only shown first in a review; a hallucinated package name that someone registered is not told apart from a real one.

**Guideline points it answers:** OpenSSF 6; Checkmarx (slopsquatting). From a review of security guidelines, 2026-10-09.

## The shape

Per ecosystem the manifests are read (Maven and npm first); the check runs on the host, through the declared
registries; the review shows each new name with its finding, and the approve waits for a person to confirm each
name that has one.

## Plan

**Today.** `ReviewRanking` (`gate/src/main/java/org/fuin/porelo/gate/ReviewRanking.java`) ranks a manifest or lockfile
`DANGEROUS` ("a dependency manifest or lockfile: it decides what code is fetched and run") and puts it first; nothing
reads what changed inside it. `GitGate.rankedReview` builds the review for `porelo gate review` (`GateReviewCommand`)
and for the daemon's `Review` (`daemon/.../GateMethods.java`, `35-gate.varlink`).

**Split into three, built in this order:**

1. **New names in the review (M).** `NewDependencies` in `gate` reads the old and the new content of each changed
   manifest from the mirror (`git show <base>:<path>`, `<commit>:<path>`) and names the coordinates the new one has
   and the old one has not: `pom.xml` (groupId:artifactId) and `package.json` (`dependencies`,
   `devDependencies`, `optionalDependencies`, `peerDependencies`). Lockfiles are left out at first: they name
   transitive dependencies nobody chose.
   `Review` gains `dependencies: []NewDependency` (ecosystem, name, version, file, finding) - a contract change;
   `gate review` prints them before the files. Red first: `NewDependenciesTest`, a pom that adds one dependency and
   keeps the rest - the review names that one and no other.
2. **The registry check and the hold (L).** For Maven Central and the npm registry a reader of the registry's
   metadata answering: exists, first published on, not found, or not reached. It runs on the host at review, only
   against registries the project declares (`core/project/Egress.java`, its egress sets) - never one it does not;
   an `offline` project's review says "not checked: offline". The review shows each name's age. Results are kept
   per name and version under the state directory. Red first: a stand-in registry answering 404 for one name; the
   review says "not in the registry" for it.

   **The hold.** A new dependency with a finding - not in the registry, not reached, not checked, or (with part 3)
   close to a known name - holds the approve back until a person confirms it. `porelo gate approve` names each such
   dependency with its finding; at a terminal it asks for each, no by default, as the bound approve asks for its
   commit, and a no forwards nothing. Without a terminal, or with `--no-input`, it is refused with exit 70 unless
   each is named with `--confirm-dependency <ecosystem>:<name>`. The daemon's `Approve` gains
   `confirmed: []string` and a new error `DependencyNotConfirmed(name: string, dependencies: []string)` in
   `35-gate.varlink`, raised before the gate is touched; a contract change announced on the channel. A
   confirmation is bound to the reviewed commit and journalled with it, so a later push asks again. Red first: an
   approve of a commit whose pom adds a name the stand-in registry does not know, which forwards today.
3. **Close to a known name (S).** A list of well-known names per ecosystem shipped with Porelo; a new name one or two
   edits away, or with a separator swapped, is named "close to <name>". Red first: `expres` named close to
   `express`.

**Docs:** the gate section of `doc/how-it-works.md` and the review row of `doc/reach.md`.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A commit that adds a Maven or npm dependency with a finding is not forwarded until a person confirms it: asked at
  a terminal, refused without one unless `--confirm-dependency` names it. Seen to fail: an approve without a terminal
  that forwards such a commit.
- A new dependency without a finding is marked in the review and does not hold the approve.
- The documentation page that describes the area says it.

## To be checked

- Where the list of well-known names comes from and how it stays current (Agent Core's).
- How the interface shows a new dependency and its finding (Agent Frontend's).
