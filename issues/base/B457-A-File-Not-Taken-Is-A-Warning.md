# B457 — A File Not Taken Is A Warning

```json
{
  "$schema": "issue-1.json",
  "id": "B457",
  "title": "A File Not Taken Is A Warning",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 93,
  "blockedBy": [],
  "covers": "A start going on without a `project.yml` that was not taken warns in color."
}
```

**Why this size.** one way of saying it, one sentence, one field.

**What must be true.** A start going on in `default` because a `project.yml` was not taken does not pass as one more line.

The operator's word, 2026-11-05, in their test of `utils4j` with their own `project.yml`: "Das muss als Warnung in Farbe sein!"

## As built

- At a terminal, `porelo: warning: ...` in yellow, Porelo's color for a warning: when standard error is a terminal, and never under
  `NO_COLOR`. By that descriptor rather than picocli's `Ansi.AUTO`, which looks at the console, as a reading found it painted a log taking
  `2>`. The word stays without color. That is every reason a file is refused, and a file nobody was there to approve. A person's own No
  stays a plain line, as they have just said it.
- The repository's name: `project.name 'utils4j' is also the name of a repository this file names, and they must differ. For a project of
  this one repository, write project.name as 'utils4j-project', for example; in a project already in use, rename that repository instead.`,
  and the line ends with the start running in `default`, not from this file. The second way is from the same reading: a renamed established
  project would no longer match its own repository.
- `method FirstStart` grows `refusal: string`. Until now a refused file reached an interface as `ASK_UNKNOWN_FILE` with no file, no hash and
  no reason.
