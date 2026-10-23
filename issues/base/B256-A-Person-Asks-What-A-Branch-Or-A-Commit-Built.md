# B256 — A Person Asks What a Branch or a Commit Built

**Status:** Idea; blocked by [B253](README.md#b253).

**What must be true.** A person asks any forge a project names what a branch or a commit built, with one command:
`porelo build status --project=<project> --repository=<repository> --branch=<branch>`, or `--commit=<sha>`, with
`--logs=none|failure|all` (default `failure`) and `--json`, `-p` and `-r` as in `porelo task start`. Project and
repository are named by their keys, never a URL: the adapter, its address and its credential come from the project
file's `builds:`, the token from the vault, and the token appears nowhere; a project without `builds:`, `default`
among them, is refused naming why.

## Why

From porelo-project [PJ62](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj62): a person
asks any of the forge adapters through one command of Porelo's. Today Porelo asks an adapter only from the helper
that follows an online task's pushes (`porelo task watch-builds`), and there is no `porelo build` command. The
command asks the adapter over the build contract's second version, so it waits for [B253](README.md#b253).
