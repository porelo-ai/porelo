# B750 — A Checkout Joins Default Under Its Own Name

```json
{
  "$schema": "issue-1.json",
  "id": "B750",
  "title": "A Checkout Joins Default Under Its Own Name",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 75,
  "blockedBy": [],
  "covers": "A start in a checkout with no project named adds it to `default` under the checkout's directory name, as the story of work without a project says, not under its origin's name.",
  "openQuestions": 0
}
```

**Why this size.** S: the name a checkout gets in `default`, the documentation that says it, and the tests. Estimate:
45 minutes (Core's median for S).

**What must be true.** A person who starts in their checkout finds it in `default` under the name they see in their
own directory, so `-r`, `project default remove` and `task list` all use the name they know.

Found on a test VM while playing [B682](README.md#b682) (2026-11-13). Work without a project says the start goes into
`default` under the checkout's name. A clone named `chk-mark` of an origin `…/o.git`:

```
$ cd chk-mark && porelo task start markchk --agent stub --detach --clearance deny --no-input   # exit 0
$ porelo project default list
o                    /tmp/tmp.…/chk-mark
$ porelo project default remove chk-mark
porelo: 'default' has no repository called 'chk-mark'
```

The story script did not see this, because each of its checkouts shares its name with its origin.

## Plan

- `WorkOrigin.fromCheckout` (`apps/app-model`) calls `DefaultProject.add(path, null, path, origin)`. With no name,
  `DefaultProject.add` names the entry after `remote`, the origin, and falls back to the address, as its Javadoc
  says. Pass the checkout's directory name (`top.getFileName()`) as the name instead. A name already taken by another
  upstream gets the next unused one through `unused(…)`, as now.
- The start's line `added to 'default' as '<name>'` stays as it is. Any sentence in `doc/` that says a checkout is
  named after its origin is corrected.
- First red, in `apps/app`: `DefaultProjectTest`, or the start's test from a checkout, uses a clone `work` of an
  origin `elsewhere.git` and expects the entry `work`.

## Acceptance

- A checkout whose directory's name differs from its origin's is added to `default` under the directory's name, and
  the start's line says that name.
- A checkout already in `default` keeps the name it has.
