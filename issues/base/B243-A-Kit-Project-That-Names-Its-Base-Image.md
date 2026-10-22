# B243 — A Kit Project That Names Its Base Image

**Status:** Analyzed.
**Size:** S - the acceptance kit's one step and its test; the step text stays, so nothing an agent repository reads breaks.

**What must be true.** A scenario in an agent repository can give the project it starts from a base image of its own,
through the acceptance kit's published steps, without editing the fixture by hand.

## Why

`a project called ... whose project file also says:` (`TerminalSteps.aProjectSaying`) writes `image: base_image:
"ubuntu:24.04"` itself and appends the scenario's text after it. Indented, the text continues that block; an `image`
mapping of its own is a second root `image` key, which the reader refuses. So a scenario cannot pin or change its base
image: measured in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01)'s
live wake case, which had to replace the step's line in its own fixture and follow the project again.

## Plan

Read in `TerminalSteps.aProjectSaying` and `ProjectReader`.

1. The step always writes `image:` with `base_image: "ubuntu:24.04"`, then the scenario's text; `ProjectReader`
   refuses duplicate keys, so a scenario's own `image:` cannot be given.
2. The project file is built by a small static helper that leaves out its own `image` block when the scenario's
   text has a root-level `image:`; otherwise it writes what it writes today.
3. **Red first:** `TerminalStepsTest.aScenarioThatNamesAnImageOfItsOwnIsTheOnlyImageInTheProjectFile`.

## Acceptance

- A suite scenario whose text names `image:` with a pinned `base_image` follows, and its task's image is built from
  that pin. Seen to fail first: the duplicate-key refusal at `project follow`.
- A scenario that gives only an indented snippet still gets `ubuntu:24.04`.

## To be checked

- The refusal `project follow` prints today for a doubled `image`, measured on the VM.
