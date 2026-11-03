# B336 — A Repository Says Whether A Merge Needs A Review

**Status:** Implementing.
**Size:** S - estimated 136 minutes from the median of S builds: one key in the project file's schema and reader, its
documentation, and the value handed on where the host reads it.

**What must be true.** The project file says for each repository whether a pull request on the host's forge needs
another person's approval before it is merged: `repositories.<name>.review`, and `project.review` for the project's
own repository, is `required`, the default when the key is left out, or `optional`, where the author merges their
own. Any other value is refused when the file is read, naming the key. Porelo only reads and checks the value; the
host's forge enforces it.

`project verify` went with the following by hand and comes back, offline, in `commit verify`'s form:
`porelo project verify --repository GIT-DIR --signers FILE --json`, with no network and no access repository, on a bare
copy another account may own. The host runs it at each follow, so the value comes only from a file checked against the
keys of the project's admins:

- `"project_review": "required" | "optional"` for the project's own repository; the answer's `"project"` stays the
  project's name;
- `"repositories": {"<name>": {"review": "required" | "optional"}, ...}`, every repository the project file names,
  in the order written; `{}` for a project naming none;
- a missing key is answered as `"required"`; a value other than the two makes verify refuse with exit 65 before it
  answers, naming the key;
- `"central"` beside `review`: `repositories.<name>.central`, where the host takes the repository from, or `null`. Its
  last path segment must equal `upstream`'s, or the file is refused, since the forge names the repository after it.

## Acceptance

- `required`, `optional` and a missing key are read, for the project and for a repository; a missing key reads as
  `required`.
- Any other value is refused, naming `project.review` or `repositories.<name>.review`.
- `verify --json` answers both fields and `central` as above, from the verified commit only.
- Being in the project file, a change to either needs a signature by a key in `signers`.
- Seen to fail first: today `review` is an unknown key, read with a warning and answered nowhere.
