# [B537](README.md#b537) — One Host File That Does Not Read Hides No Other Project

**Status:** Backlog. Filed 2026-11-09 from [B531](README.md#b531)'s code reading, as the operator decided it
(the operator's decision of 2026-11-09 on the open points of wave 2, point 4: B, after move 4).
**Size:** S - a field on the project row, the listing reads past one bad file, and the window shows it.

**What must be true.** On a host, a carried project whose host file does not read is listed as a row saying so
(`hostFileError`), and every other project is listed as usual; the file is still said, never passed over as not carried.

## Plan

- Today one such file fails the whole listing (`OrganizationProjects.hosted` throws; an earlier decision, held by
  `OrgReadingCommandsTest.aHostFileThatDoesNotReadIsSaidNotPassedOver`, that it is said).
- `Project` row gains `hostFileError: ?string` (record and 20-project.varlink); `ProjectInventory.projects()` catches the
  one file's `IOException` into that row; `porelo project list` shows it; the test above becomes: the bad project is a
  row with the error, the others are listed. Red first.
