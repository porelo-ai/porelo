# B431 — Change A Repository Row In One Commit

**Status:** Review.
**Size:** S - one module's new subcommand and one write in `ProjectFile`, with tests; the file's format does not change.

**What must be true.** A Project Admin changes a row of `project.yml` in one signed commit, and every field the change does
not name stays as it was.

## Why

`porelo project repository add` refuses a name already there, so giving a row a `central` takes `remove` and then `add`.
That is two commits, with a moment between them when the row is gone. `add` knows only `upstream`, `central` and `review`,
so a `description`, `issues` or `agent` written by hand is lost and must be put back by hand.

## Plan

- **`ProjectRepositoryCommand.Change`,** beside `Add` and `Remove`: `porelo project repository change PROJECT NAME [--upstream ADDRESS]
  [--central ADDRESS] [--review required|optional]`, the same rights, and `--id` and `--base` of the project repository. Naming
  none of the options is a usage error.
- **`ProjectFile.changeRow`,** by the existing `change`: `not-found` for a name the file does not hold, as `remove` answers; the
  named fields replaced and every other field kept; a subject from `TreeRepository.asked`, so a retry under the same id is
  the same change. A `central` ending in another name than `upstream` is refused before the commit, by the rule of
  `ProjectReader.central`, whichever of the two was named. A change altering nothing writes no commit, and says so.
- **Not here:** taking a `central` away. A row with none is refused on a host; `remove` and `add` still do that.
- **`doc/commands.md`** names the command.
- **`project show --json`** gives each row of `repositories` its `central` as written, `""` where it has none, so an interface can name
  a row a host takes nothing from and offer this change. A project with one `upstream` and no row has no such field.
- **Red first:** `ProjectFileTest`: a row with `description`, `issues` and `agent` keeps them through a change of `central`.

## Acceptance

- A row with `description`, `issues` and `agent` keeps them through a change of `central`.
- Asked again under the same `--id`, it gives the same answer and no second commit.
- A `central` ending in another name than `upstream` writes nothing.
- `doc/commands.md` names the command.
