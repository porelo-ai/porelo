# B202 — Credential Names In A Project File Checked

```json
{
  "$schema": "issue-1.json",
  "id": "B202",
  "title": "Credential Names In A Project File Checked",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 12,
  "blockedBy": [],
  "covers": "A credential's name in a project file is checked when read, since it becomes a token file's and a variable's name; what an existing file does is decided."
}
```

**What must be true.** A credential's name in a project file is checked when the file is read, against the names that
can safely become what Porelo makes of them, and a name outside them is refused by name. What happens to a file that
holds such a name today is decided and said.

**Why this size.** one rule in `core` used at three places that already exist, the run's own names and two documents; no
contract change. Estimate 45 minutes, porelo's median for S.

**Why.** Found in the rereading of `app-vault` against the house skills (2026-10-17): a credential's name is not checked
where it becomes a route's token file and a variable's name in a task. Checking it narrows the project file's format, so
the analysis settles which names are allowed - letters, digits and which separators - and whether an existing file that
holds another is refused, warned about first, or kept working until a stated release.

## Found

Read in the code at 177f3cae2:

- **A project file's names are checked already**, since the typed reader came: `ProjectFileCheck.credentials` and
  `Project`'s constructor refuse an entry outside `[a-z0-9][a-z0-9_-]{0,30}` and a destination outside
  `[a-z0-9][a-z0-9-]{0,30}`, naming both. `doc/project-file.md` still says Porelo does not check them.
- **A run's own names are not checked:** `task start --credential <entry>=<destination>` and the daemon's `Start` and
  `CanStart` (`credentials: [string]string`) reach `CredentialWiring` unread. There an entry becomes the route's token
  file `routeTokens.resolve(name + ".token")` in `vault serve`, and the variables `PORELO_TOKEN_<SUFFIX>` and
  `PORELO_URL_<SUFFIX>` by `variableSuffix`, which upper-cases and turns `-` into `_`. An entry `../x` names a file
  outside the route tokens' directory, and one holding `=`, a space or a newline names no variable.
- **Two allowed names can become one variable:** `a-b` and `a_b` both become `PORELO_TOKEN_A_B`; the second overwrites
  the first in the task's environment, so a task reaches one destination with the token it meant for the other.

## Decisions

- **The allowed names stay as a project file already has them**, `[a-z0-9][a-z0-9_-]{0,30}`: every name in this
  repository's documents and tests fits (`search`, `search-key`, `forge-app`, `upgrade-route`, `buyer`,
  `anything-at-all`), each becomes a file name with no separator and a variable name, and narrowing them would break
  files that work. The same rule holds for a run's names.
- **Two names that become one variable are refused, both named**, in a project file, among a run's names, and between
  the two: such a file today hands one token out under the other's variable.
- **An existing file holding another name** is refused by name when it is read, as it has been since the typed reader;
  nothing is kept working, since no such file can start a task today.

## Plan

- **`core/.../project/CredentialName`**: the pattern, `variable(name)` (moved from `CredentialWiring.variableSuffix`,
  which then calls it) and `clashes(names)` returning each pair that becomes one variable.
- **`ProjectFileCheck.credentials` and `Project`** use it, and fault on a clash at `credentials.<second>`.
- **A run's names:** `StartCheck.undeclared`, which `task start` and `CanStart` share, refuses an entry outside the
  pattern and a clash with the project's or another run's name, before anything is created, naming the entry; the
  daemon's `Start` goes through the same check.
- **Documents:** `doc/project-file.md` states the rule instead of saying none is checked; `doc/credentials.md` and the
  `--credential` help name it.
- **Level:** unit tests only. **Red first:** `StartCheck` with a run's entry `../x` for a declared destination answers
  no refusal today; `ProjectFileCheck` with `a-b` and `a_b` answers no fault today.

## Acceptance

- `task start --credential ../x=search` and a daemon `Start` with that entry are refused naming `../x`, and nothing is
  created; no file is written outside the route tokens' directory.
- A project file with `a-b` and `a_b`, and a run adding `a_b` to a project's `a-b`, are each refused naming both.
- Every name the documents and tests use still reads and starts as before.
