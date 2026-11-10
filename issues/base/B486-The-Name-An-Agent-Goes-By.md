# B486 — The Name An Agent Goes By

```json
{
  "$schema": "issue-1.json",
  "id": "B486",
  "title": "The Name An Agent Goes By",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 76,
  "blockedBy": [],
  "covers": "Without a nickname an agent goes by \"Agent \" and its key capitalized, taken as it is; a nickname may be several ASCII words; colon, comma, control and non-ASCII characters and double spaces refused; no two names alike without case or with spaces collapsed, nor one the start of another before a space."
}
```

**Why this size.** the nickname's rule, the default name, the one check that two names differ, the schema and the docs.
Estimate 75 minutes.

**What must be true.**
- **The default name.** Without a nickname, a repository's agent goes by "Agent " plus its key with the first letter
  in capitals: `core` goes by "Agent Core". The project's own repository's agent does the same with the project's
  name.
- **A nickname of several words.** A nickname may be several words of ASCII letters, digits and hyphens. The words
  are separated by single spaces, a letter comes first, and there is no leading or trailing space. "Agent " stays
  fixed in front, so no agent can go by a person's name alone.
- **What is refused.** These are refused in Porelo's own words: a colon, a comma, control characters, any non-ASCII
  character (look-alike letters) and double spaces.
- **One name per agent.** Two repositories of a project, its own among them, may not go by one name. Names are
  compared without case and with spaces collapsed. Nor may one go by another's name followed by a space and more
  ("Agent Sea" beside "Agent Sea Lion"), since a reader matching a whole phrase from the left would take the
  shorter one.
- **A default name is taken as it is.** A key starting with a digit or holding a dot gives "Agent 3d" or
  "Agent Web.app", although a nickname may hold neither.
- **Every use keeps working.** These are the commit author (`PORELO_GIT_NAME`), `project list`'s `goesBy`, and a
  conversation.
- **Existing files.** An existing nickname stays valid. A file without one changes only in the capitalization of the
  name.

## Found (today's code)

- **One place forms the name.** `Repository.goesBy()` returns `"Agent " + (nickname == null ? name : nickname)`.
  `Project.ownRepository()` builds the project's own repository from the project's name and `project.nickname`.
- **The rule is held three times, alike.** `Repository.NICKNAME_PATTERN` is `[A-Za-z][A-Za-z0-9-]{0,31}`. It is
  checked in `Repository` (for a repository), in `Project` (for `project.nickname`) and in `ProjectFileCheck`, each
  with its own message, "one word of at most 32 letters, digits or hyphens".
- **One check keeps two names apart.** `ProjectFileCheck` compares `nickname ?? key` in lower case across the
  repositories. It compares the project's own name the same way at the point where the project's own repository is
  checked. It does not collapse spaces yet: today a name has none.
- **Where the name is used:**
  - `TaskWorkspace` puts `goesBy` into `PORELO_GIT_NAME`. The workspace runs
    `git config user.name "${PORELO_GIT_NAME:-agent}"` in double quotes, so a name with spaces is set whole. The
    mail stays `agent@localhost`.
  - `ProjectInventory` answers `goesBy` in `project list`. The window shows it as given and derives nothing.
  - `porelo gate check` only shows a refused commit's author; nothing in Porelo compares author names.
- **Conversations.** Porelo's own `talk` addresses a message by its `metadata.to` (`MessageDispatch.addressees`), a
  project's name, and reads no text head. The room the agents of this work use reads a head
  ("Agent Core, Agent Sluice:") by the pattern `Agent [A-Z][A-Za-z0-9-]*`: one word with a capital letter first.
  There, "Agent Sea Lion" would be read as "Agent Sea". That room's tools are no part of Porelo and ship nothing.
- **The message sluice reads no head either.** It goes by `metadata.to` and `metadata.from` alone, never resolves
  them, and passes the text only to its detectors. It holds both fields to Porelo's peer rule,
  `[a-zA-Z0-9][a-zA-Z0-9_.@-]*` or `task:<project>:<task>`, and refuses a name with a space there as
  `ENVELOPE/PEER_NAME_MALFORMED`. The name an agent goes by never reaches those fields: `goesBy()` is read only by
  `TaskWorkspace` and `ProjectInventory`, nothing in `app-messaging` reads it, and the window uses it only in the
  sentences it shows. A change that put it there would have to change the peer rule in both repositories.

## Built

- `Repository.NICKNAME_PATTERN` and `Repository.NICKNAME_RULE` are the one rule and its words; `Project` and
  `ProjectFileCheck` use both. `Repository.goesBy(nickname, name)` forms the name, and `Repository.compared` is how two
  are compared. `ProjectFileCheck.apart` refuses two names alike and one that another's starts with before a space.
- Tests seen red, then green: `ProjectRepositoryAgentTest`, `ProjectFileFaultsTest`, and in `apps/app`
  `TaskWorkspaceTest` (a commit authored as "Agent Sea Lion" by real git) and `ProjectInventoryTest`.
  `task-repository-agent.feature` now commits as "Agent Sea Tester" in a task.

## Reading

One reading by another model. Its two test gaps were closed: the prefix refused when the longer name comes first, and
the 32-character boundary. Each new test was seen red against a deliberate break. Declined:
- **A trailing space or an empty nickname gives a second, oddly worded fault beside the rule's.** The file is refused
  by the rule either way, so only a message reads wrong.
- **`Project` does not repeat the check that two names differ.** Its one caller that replaces repositories,
  `HostForgeAddress.placed`, keeps each row's nickname from a file already checked.
- **A repository whose key is the project's name drops out of the comparison.** Such a file is refused by its own
  check.
- **The pattern is compiled per call, and the comparison is quadratic in the repositories.** A project has a handful
  of repositories.

## Plan

1. One rule in `Repository`: a pattern for words separated by single spaces, ASCII only, a letter first, with the 32
   characters kept as the length. `Project` and `ProjectFileCheck` use it and its one message, which names what is
   refused: a colon, a comma, a control character, a non-ASCII character, a double or outer space.
2. `goesBy()` capitalizes the first letter of the key or the project's name when no nickname is given. A nickname is
   taken as written.
3. The check that two names differ compares the name as it is gone by, in lower case with spaces collapsed, the
   project's own included, and also refuses a name that another's starts with, followed by a space.
4. The schema's description, `doc/project-file.md` and the changelog.
5. Tests, red first:
   - the default name capitalized, for a repository and for the project's own;
   - a nickname of three words accepted;
   - each refused form refused with its words;
   - "Sea  Lion" against "sea lion" refused as one name;
   - "Sea" beside "Sea Lion" refused, and "Sea" beside "Seal" accepted;
   - a key `web.app` going by "Agent Web.app";
   - an existing one-word nickname unchanged;
   - `PORELO_GIT_NAME` set whole with a space.

## What it does to what exists

- **Commits already made keep their author.** Git history does not change, so a repository's log shows
  "Agent core" up to the change and "Agent Core" after it. `git shortlog` counts those as two authors, since it
  compares names with case. The mail is the same, so a `.mailmap` line joins them where a person wants that.
- **A running or kept task keeps the old name.** This is inferred from the code, not measured: its container's
  `user.name` was set when its workspace was made, and its environment is fixed with the container. A task started
  after the change goes by the new name.
- **`project list` answers the new `goesBy` at once.** The window shows it as given.
- **Nothing in Porelo is keyed by the name,** so nothing is lost, orphaned or refused by the change.

## Answered (the operator, 2026-11-07)

- A default name from a key with a digit first or a dot is taken as it is.
- A name that another's starts with, followed by a space, is refused.
- `issues:` stays a list; it is no part of this issue.
- The room tools used by the agents of this work are brought to the new rule by the Coordinator, before a name of
  several words reaches an agent there; that is outside Porelo.
