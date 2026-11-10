# B416 — A Forge That Refuses A Host's Read Key Says Why

```json
{
  "$schema": "issue-1.json",
  "id": "B416",
  "title": "A Forge That Refuses A Host's Read Key Says Why",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 21,
  "blockedBy": [],
  "covers": "A forge's refusal of a host's read key says why, naming the organization's deploy key setting and its page; an organization at GitHub must allow deploy keys for a host to read it"
}
```

**Severity.** medium: a host cannot be set up, and the person is not told the reason or where to change it.

**What must be true.** When the forge refuses a host's read key, the person reads why. Where the organization has deploy keys
turned off, the refusal names that setting and its page. Before a host is prepared, an organization's owner knows this is
required of it.

**What holds today.** Seen in a walk at GitHub: `org add-read-key` answered "the forge did not take the key, answering 422; it may
stand on another repository or on a person's account there". The organization's page
`https://github.com/organizations/<org>/settings/deploy_keys` read *Disabled*: "Deploy keys are not allowed to be created and used
within this organization." The key was minutes old, so it stood nowhere else. GitHub's words on that 422 are not measured.

Read in the code: `ForgeReadKey.add` lists the repository's keys, then posts the key, and quotes nothing of the refusal, since a
forge's words could hold the token sent. The token is known there, so it can be cut out of them.

**What it requires of an organization.** A host has no other way in today, read in the code. `org fetch` runs with no person, no
vault and no token, and reaches the organization by an ssh key alone (`HostKey`), and `add-read-key` is the one way that key gets
to the forge. So an organization at GitHub that keeps deploy keys off cannot have a host read it. That goes into `doc/hosts.md` and
the join as a requirement until something else is decided.

A key on a machine account would pass `org fetch`, which does not ask what kind of key it holds. It is not offered: nothing makes
or registers one, and its account reaches more than this repository, against what a host is promised, that it only reads this one.

A project's repository meets the same setting, read in `porelo-host`, not run at GitHub: its migration posts the same call and is
refused the same way, in that repository's own organization.

**To be checked.** Measured with a second GitHub account, with no membership in `porelo-ai`, which the operator makes after the
trial moves (2026-11-08).

- GitHub's words on this 422, and on one for a key in use elsewhere, so the refusal can tell them apart.
- How Forgejo answers when deploy keys are off, if it can turn them off.
