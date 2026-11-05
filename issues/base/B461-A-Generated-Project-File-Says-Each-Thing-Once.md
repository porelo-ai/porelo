# B461 — A Generated Project File Says Each Thing Once

**Status:** Backlog.
**Size:** S for the generator as the file stands; the format is the operator's, below, and changes the size.

**What must be true.** A person reading a generated `project.yml` can tell which place holds for `image` and `egress`, and nothing in it misleads.

The operator's finding, 2026-11-05, from their test. Nothing is built, and the format is not changed, before their choice.

## Found

A generated `project.yml` writes `base_image: ubuntu:24.04` twice: under `image` at the top and under the repository, both fixed in
`ProjectProposal.java`. Whichever is read, the other misleads. The operator judges the format itself broken: a reader cannot tell which place holds.

**Read while filing, and worse than the duplicate:** the order is the local override, then the project file's entry for the repository, then the
repository's own `porelo.yml`, then the project's `image` (`doc/project-file.md`, `ImageChoice`). So what the generator writes under the repository
beats that repository's `porelo.yml`, which the person may never have meant.

## The Two Ways

The operator is choosing. `egress` under a repository is added to the project's for that repository's tasks; `image` there replaces key by key.

### One place as the normal case, a repository's own entry only where several differ

- **Reader:** nothing; it reads this already.
- **Generator:** `ProjectProposal` writes `image` and `egress` at the top only, the snippet with them. Its tests and `a yes writes project.yml` follow.
- **`porelo.yml`:** unchanged, and it now wins over a generated image, as the order means.
- **Docs:** `doc/project-file.md` says this is the normal case, and when a repository's entry is worth it.
- **Files already approved:** nothing moves. They read as they do, and only new drafts differ.
- **What it costs:** a second repository added later has the first one's egress, as the project's. Today that would stay with the first.
- **Size:** S.

### No repository level for `image` and `egress`

`repositories` stays, for `upstream`, `limits`, `review` and the others.

- **Reader:** `ProjectReader`, `ProjectSchema`: both keys refused by name, as removed settings are.
- **Code using them:** `ImageChoice`'s order, `EgressReport`'s origins, `EgressEdit` and `EgressControl`'s per-repository writing; about 5 files with tests in each of `apps` and `core`, by a search, and more where a test holds a file.
- **`porelo.yml`:** it remains a repository's only own image, so a repository can still differ there, checked in and read from reviewed work.
- **Egress loses something:** a repository can no longer reach what another of the project's cannot. All tasks of a project reach the union. That is a security decision, against the rule in `doc/project-file.md` that a set moves into the repositories needing it.
- **Docs:** `doc/project-file.md`, `doc/decisions.md`, and the window's schema by `ProjectFileSchema`.
- **Files already approved:** they stop reading. What was approved is this machine's copy, a person's yes, so Porelo may not rewrite it unseen, and a signed one needs its Project Admin. So a release reads the old keys with a warning and a proposed file for a new yes; moving a repository's egress to the top widens the others, and that person must see it.
- **Size:** L.

## Not Read Yet

How many approved files exist with the repository's entry, and whether `project init` wrote them before the first start did.
