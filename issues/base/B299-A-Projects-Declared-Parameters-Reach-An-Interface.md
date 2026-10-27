# B299 — A Project's Declared Parameters Reach An Interface

**Status:** Idea.

**What must be true.** The daemon's `Projects` answer carries the parameters a project file declares under
`parameters:` - each name, its description, whether it is required, its allowed values or pattern, and its default -
so an interface asks for them before `Start` instead of learning them from a refusal.

## Why

porelo-frontend [F148](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f148) asks for a
project's values once for all its repositories. Read in porelo at 0b02c1b7: `project.yml` already declares them, read
into `Parameters` and `Parameter` (name, description, values or pattern, default, required, env) in `core`, and
`Start` and `CanStart` take `parameters` and check them; but `Tasks1`'s `Project` type carries no declaration, so an
interface sees a parameter only in the prose of a refusal.

## To be checked

- Whether `env` - the variable a value reaches the task as - belongs in the answer, or stays the host's concern.
- That the field only grows `Tasks1`, as its header requires.
