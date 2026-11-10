# B436 — A Published BOM Test Runs From A Clean Tree

```json
{
  "$schema": "issue-1.json",
  "id": "B436",
  "title": "A Published BOM Test Runs From A Clean Tree",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 17,
  "blockedBy": [],
  "covers": "`PublishedBomTest` needs a generated file, so a targeted test run in a clean tree fails."
}
```

**What must be true.** `PublishedBomTest` passes or says what it needs in a tree nothing was built in, whichever modules are named.

## Why

A bug: it reads the generated `bom/.flattened-pom.xml`. In a fresh worktree `./mvnw -pl daemon,apps/app -am test` fails with
`NoSuchFileException`, and the reactor stops before `daemon`. A tree that has built once passes, which hides it.

## To be checked

- Whether `apps/app` should name the BOM so that the build makes the file first, or the test should find it another way.
- Whether a clean full `verify`, as the Build runs it, is affected. Not seen so far.
