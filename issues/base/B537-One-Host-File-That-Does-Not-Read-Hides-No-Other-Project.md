# B537 — One Host File That Does Not Read Hides No Other Project

```json
{
  "$schema": "issue-1.json",
  "id": "B537",
  "title": "One Host File That Does Not Read Hides No Other Project",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "On a host, a project whose host file does not read is a row saying so, and the others are listed."
}
```

**Why this size.** a field on the project row, the listing reads past one bad file, and the window shows it.

Filed 2026-11-09 from [B531](README.md#b531)'s code reading, as the operator decided it
(the operator's decision of 2026-11-09 on the open points of wave 2, point 4: B, after move 4). Implementing since 2026-11-10, on the
coordinator's order of 14:17Z (ST290 is Highest). Built by Codex under Core's supervision; in Review since
2026-11-10, until the operator's test.

**What must be true.** On a host, a carried project whose host file does not read is listed as a row saying so
(`hostFileError`), and every other project is listed as usual; the file is still said, never passed over as not carried.

## Plan

- Today one such file fails the whole listing (`OrganizationProjects.hosted` throws; an earlier decision, held by
  `OrgReadingCommandsTest.aHostFileThatDoesNotReadIsSaidNotPassedOver`, that it is said).
- `Project` row gains `hostFileError: ?string` (record and 20-project.varlink); `ProjectInventory.projects()` catches the
  one file's `IOException` into that row; `porelo project list` shows it; the test above becomes: the bad project is a
  row with the error, the others are listed. Red first.

## As built

- **Red first:** `OrgReadingCommandsTest.aHostFileThatDoesNotReadIsSaidNotPassedOver` now holds two carried projects,
  `demo` with a file that does not read and `other`; it was red (`expected: 0 but was: 70`). `porelo project list`
  exits 0, lists both, and says `host_file_error` (the file and why) on `demo`'s row, in text on its line.
- **`OrganizationProjects`:** `hostedRows(account)` answers per project a `HostRead`, either what the host says or why
  its file does not read; a file that does not read as UTF-8 names its path too. `hosted(account)` stays strict.
- **`Projects()`:** the varlink `Project` has `hostFileError: ?string`; `ProjectInventory` keeps the other projects
  when one file fails, where it used to drop every host project. A row with an error is never carried.
- **The person's own list** (`persons/<account>.json`) is no project's file: `porelo project list` still fails on it
  and names it (`aPersonsProjectListThatDoesNotReadStillFailsTheCall`); `Projects()` then names no host project and
  lists this machine's projects, as before, so the window's list never fails over it.
- **Tests:** `ProjectInventoryTest.oneBadHostFileKeepsItsErrorAndEveryOtherProject` (bad JSON and bad UTF-8),
  `aPersonsUnreadableProjectListHidesNoProjectOfThisMachine`,
  `InterfaceDescriptionTest.aProjectDeclaresItsOptionalHostFileError`.
  `doc/commands.md`, `doc/hosts.md` and a changelog entry say it.
- **Left:** the desktop window shows nothing of `hostFileError` yet; it is a field for it there.
