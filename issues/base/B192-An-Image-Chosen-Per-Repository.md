# B192 — An Image Chosen Per Repository

**Status:** Analyzed.
**Size:** L - a new file format with its reader and refusals, four places to read it from in a fixed order, a read of a
mirror's default branch that Java does not make today, one image per repository with its own cache, the start and its
dry run naming the source, a review rank, the docs and acceptance steps; several modules and one new contract a
repository's authors write.

**What must be true.** A repository says what it needs to be built in a file of its own, and a task started on that
repository gets an image made from it. The file never carries the rules a task runs under. The start, and its dry
run, name the file the image came from.

From [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39), phase 2, where Porelo's own
work moves into Porelo tasks, one task per issue, phase and repository. Decided 2026-10-18; it replaces the
devcontainer file as a source, which would have given only `image` or `build`, with the tools mostly behind
`features` that Porelo does not fetch.

## The decided shape

- **`porelo.yml`**, checked into the repository, holds `image:` with `base_image`, `snippet` or `snippet_file`, and
  `env`. Nothing else: egress, limits, the class and every other key are refused by name, so a repository cannot widen
  what its own tasks may do.
- **`.porelo.yml`** has the same format, is never checked in, and overrides it locally.
- **What wins, key by key** (decided 2026-10-18): along the order below, the first source that sets a key gives it.
  `base_image` and the snippet (`snippet` or `snippet_file`) are each taken whole from one source; `env` is merged
  per variable. A local `.porelo.yml` that sets only one variable changes only that one.
- **The order:**
  - in a project with a `project.yml`:
    1. the person's `~/.config/porelo/projects/<project>/<repository>/.porelo.yml`;
    2. `project.yml`'s `repositories.<name>.image`, which is new;
    3. `porelo.yml` from the mirror's default branch: reviewed work only, never what a task pushed;
    4. the project's `image`;
  - in the project `default`, a task started from a person's checkout:
    1. `.porelo.yml` in that checkout;
    2. `porelo.yml` in that checkout;
    3. the default image.
  Approved work lands on `porelo/<task>`, never on the checked-out branch, so a task cannot change either file under
  the person.
- The start and `--dry-run` name the source, for example "image from …/.porelo.yml (local override)".
- The review ranks a change to `porelo.yml` as dangerous, like `.devcontainer/` and `.github/`, since the image is
  built outside the task's firewall, as root.
- Each repository's image is built once and cached, as a project's image is today.

## Plan

Read in the code on 2026-10-18.

1. **The format.**
   - A reader `RepositoryImageReader`, in `core/.../project/`, beside `ProjectReader`. It takes `image:` with the keys
     `ProjectSchema` allows there today (`base_image`, `snippet`, `snippet_file`) plus `env`. It reuses
     `ProjectReader.snippet` (`ProjectReader.java:756`): inline and file forms are exclusive, and `snippet_file` is
     resolved against the file's own directory and must stay inside it.
   - `package_sources` stays the project's: it names where packages are trusted from, which is a rule.
   - Every other top-level key, and every other key under `image`, is refused with its name and "only image may be set
     here".
   - The result is a record `ImageSource(base, snippet, env, from)`, where `from` says which file or entry it came
     from. `Project` keeps its fields; `ImageSource.of(project)` is step 4.
   - `env` becomes `ENV` lines in `Containerfile.render` (`runtime/.../Containerfile.java:166`). A name Porelo sets in
     the task (`PORELO_*`, `PATH`, `HOME`) is refused.
2. **`repositories.<name>.image`** in `project.yml`: `ProjectReader.repositories` (`ProjectReader.java:309`) reads the
   same three keys through the same reader, and `Repository` (`core/.../Repository.java:42`) gains an
   `@Nullable ImageSource image`. `ProjectSchema` lists it.
3. **Reading the mirror's default branch.** Today only a followed project's clone is read with `git show`
   (`Reconcile.java:740`). The default branch is found only inside the container's script (`TaskWorkspace.java:298`).
   - A `MirrorFile.read(mirror, path)` runs `git symbolic-ref HEAD` in the bare mirror (`GateSupport.mirror`,
     `GateSupport.java:126`), then `git show <branch>:porelo.yml`, and `snippet_file` the same way.
   - It is read after the mirror's fetch, so the first start of a repository fetches before it builds.
   - Branches named `porelo/*` are never read.
4. **Choosing.** One method, `ImageChoice.of(project, repository, checkout)`, walks the order above, key by key, and
   returns the `ImageSource`, which keeps for `base_image`, the snippet and each `env` variable the source it came
   from. `TaskLaunch` (`TaskLaunch.java:864`) prints one line per key with its source in place of `base image` and
   `task image`, and so do `task prepare` and its dry run (`Preparation.java:117`).
   - For the project `default`, the checkout is `DefaultProject`'s entry (`DefaultProject.java:65`, from
     `WorkOrigin.fromCheckout`). The files are read from the working tree, as the person sees them.
5. **One image per repository.**
   - The name is `porelo/<project>` when the source is the project's own `image`. Otherwise it is
     `porelo/<project>--<repository>`, so a project that does not use the feature keeps its one image.
   - The recipe label (`Containerfile.fingerprint`, `:126`) takes in the source's base, snippet and `env`, so a changed
     `porelo.yml` makes the image stale through the comparison that exists (`ProjectInventory.java:799`).
   - The build context is `data/build/<project>/<repository>`, with its own build lock (`TaskRunner.java:273`).
   - `porelo clear` and `project clear` remove these images as they remove the project's.
6. **The review rank.** `ReviewRanking.names()` (`gate/.../ReviewRanking.java:140`) gets `porelo.yml` with "it chooses
   the image a task is built from, as root and outside the firewall". `.porelo.yml` gets "a local override, never to
   be checked in".
7. **Docs and tests.**
   - `doc/projects.md` and `doc/getting-started.md` describe the file, the order and the refusals.
   - Unit tests cover the reader, each refused key, the order with each source missing in turn, and the mirror read of
     a bare repository whose HEAD is not `main`.
   - Acceptance, beside `task-guarded.feature:29`: a project with two repositories, one carrying a `porelo.yml` with a
     snippet; a task on each gets its own image, and the dry run names where each came from. A `porelo.yml` that names
     `egress` is refused at the start, by name.

## Acceptance

- [ ] A repository whose default branch carries a `porelo.yml` with a snippet gets an image with that snippet. A task
  on another repository of the same project gets the project's image, and both dry runs name their source.
- [ ] A `.porelo.yml` under `~/.config/porelo/projects/<project>/<repository>/` that sets only `base_image` and one
  `env` variable wins for those two over `project.yml`'s entry and the repository's file. The snippet and the other
  variables still come from those, and the start names each key's source.
- [ ] A `porelo.yml` on a branch other than the default, or on `porelo/<task>`, changes nothing.
- [ ] A `porelo.yml` naming `egress`, `limits` or any key outside `image` is refused at the start with that key's name,
  and no image is built. The test is watched to fail first.
- [ ] In the project `default`, a checkout's `.porelo.yml` wins over its `porelo.yml`, and approving a task's work
  leaves both files as they were.
- [ ] The review shows a change to `porelo.yml` as dangerous.

## Open questions

- **The build's network.** A `porelo.yml` snippet runs at build time with the network the build has today, as a
  project's snippet does. Now the repository's authors write it, behind review of its default branch. Is that review
  enough, or does a repository's snippet get the project's egress at build time? That would be a change of its own,
  not in this size.

## To be checked

- Whether every mirror's bare `HEAD` follows the upstream's default branch after a fetch. `GitGate` makes a mirror
  with `--initial-branch=main` (`GitGate.java:136`), so an upstream whose default is `master` may leave it pointing at
  a branch that does not exist; then step 3 sets it from `ls-remote --symref`.
- Whether `ReviewRanking` sees a file at the repository's top only, or `porelo.yml` anywhere; only the top-level one is
  read.
