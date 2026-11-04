# B434 — A Refused Start Says What Helps There

**Status:** Idea.

**What must be true.** A start a host refuses for want of a certificate says what helps the person where they are.

## Why

A bug: `TaskLaunch` answers "add the key of your card to your agent (ssh-add) and start it again". That helps a person in a
shell on the host with an agent there, which a host does not forward. A person starting from an interface, or by ssh, has
the key in their agent already, on their own computer, and running `ssh-add` changes nothing. The way that works,
`porelo task start ... --host LOGIN@HOST` at their own computer, is not named.

## The shape

- The message names `porelo task start TASK --host LOGIN@HOST --project PROJECT`, run at the person's own computer, and what it
  is for. The login and the project are known there, so the line can be whole, up to the host's address.
- It stops saying `ssh-add`, or keeps it second, for an agent on the host.
- With [B433](README.md#b433) done, a start through the daemon no longer ends here, and the words stay for a command.

## Acceptance

- The refusal names the `--host` form with the task's project and the person's login, and it runs when copied, given the
  address.
- The refusal for a project whose access file names no certifier stays apart.

## To be checked

- The warning "cannot record the start's step" on `.start-step-….tmp` came with this refusal. Inferred, not traced: the
  refusal's clean-up took the directory before the last step was written (`StartStepFile`). If so, it should say nothing
  there, and a person should not read it as a second fault.
