# B431 — Change A Repository Row In One Commit

**Status:** Idea.

**What must be true.** A Project Admin changes a row of `project.yml` in one signed commit, and every field the change does
not name stays as it was.

## Why

`porelo project repository add` refuses a name already there, so giving a row a `central` takes `remove` and then `add`.
That is two commits, with a moment between them when the row is gone. `add` knows only `upstream`, `central` and `review`,
so a `description`, `issues` or `agent` written by hand is lost and must be put back by hand.

## The shape

`porelo project repository change PROJECT NAME [--upstream ADDRESS] [--central ADDRESS] [--review required|optional]`

- It goes the way of `add` and `remove`: `ProjectRepositoryCommand` and `ProjectFile`, the same rights, and `--id` and
  `--base` of the project repository.
- At least one option is named; a run naming none is a usage error.
- A name the file does not hold is `not-found`, as `remove` answers.
- The result is read back through the rules `ProjectReader` holds, so a `central` ending in another name than `upstream` is
  refused before anything is pushed.
- A change that alters nothing writes no commit, and says so.

## Acceptance

- A row with `description`, `issues` and `agent` keeps them through a change of `central`.
- Asked again under the same `--id`, it gives the same answer and no second commit.
- A `central` ending in another name than `upstream` writes nothing.
- `doc/commands.md` names the command.

## Open questions

- How a `central` is taken away again: `--no-central`, or not at all, since a row without one is refused on a host.
- Whether the window's rows offer it. That is the interface's to decide.
