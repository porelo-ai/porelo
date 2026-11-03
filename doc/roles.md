# Who may do what in an organization

An organization is one Git repository. Write access to that repository says nothing: every reader walks its history and judges
each file a commit changes by the key that signed the commit. A change signed by the wrong key is refused on that path, and what the
reader answers stays as it was. This page says who may change what, as built today.

## The roles

| Role | Where it is written | What it is for |
|---|---|---|
| Owner | `owners.yml` | Names owners, projects and hosts, and steps in for a card whose key is lost. |
| Project admin | `projects/<project>/members.yml`, role `admin` | Answers for one project, its people and its repositories. |
| Developer | `projects/<project>/members.yml`, role `developer` | Works in one project. |
| Host admin | `hosts/<host>/host.yml`, `admins` | Answers for one host. |

The roles are separate. An owner is no project admin and no host admin by being an owner, and none of the others is an owner. A
person who is one holds a right only with the key on their card, while that card is trusted and its key not revoked. A key whose card
the forge has not yet vouched for neither passes nor fails: the reader waits for the forge's word. A project admin who is locked
holds no admin's right there.

## The files

Each path is judged alone. A path not listed here is never read.

| File | Who may change it | What else holds |
|---|---|---|
| `owners.yml` | An owner | The first one names its signer, whom the forge must say owns the organization. It cannot be removed. An owner's backup key may only put one new key in place of the one it backs. |
| `people/<login>.card` | The card's own key; an owner in place of a refused card or a lost key's | A change of key is handed on by the old key, or made by an owner or a backup that login filed, which revokes the old key. Removing a card, by its own key or an owner, revokes its key too. |
| `projects/<project>/card` | An owner | Adds the project with its first admin, names another, or removes the project, which also leaves its host. |
| `projects/<project>/members.yml` | A project admin; an owner only for admins | The card's admin writes the first one, naming themselves an unlocked admin. An owner may add and remove admins and change a role to or from admin, and nothing else there, no lock above all. It goes only with its project. |
| `projects/<project>/repository.yml` | A project admin | The project's address. It goes only with its project. |
| `hosts/<host>/host.yml` | An owner, to add or remove the host; after that a host admin | A new host holds one admin and nothing else. A host admin changes its admins and the projects it carries. A backup key in it can be named only by the person it backs. |
| `hosts/<host>/card` | A host admin | An owner may remove it only together with `host.yml`. |

## A project's own file

`project.yml` does not stand in the organization. It stands in the project's own repository, which the organization's address names,
and only a machine reading that repository judges it: the newest commit that changed the file must be signed by the key of a project
admin who is one now and is not locked, within their card's days. Another signature there, or none, leaves the file refused until an
admin signs it anew with `porelo project sign`. See [how the file reaches a machine](project-file.md#how-the-file-reaches-a-machine).

## On a host

A host takes the organization at each follow, against a pin of the keys it holds for its admins. An owner does not move that pin: an
owner's change on the host's paths, signed by a key the pin does not hold, leaves the host at `refused-state` until its admin renews
the pin on the machine. See [why](decisions.md#an-owner-does-not-move-a-hosts-pin).

A follow gives root on a host to its host admins' keys. How a project's member comes to an account there is
`porelo-host`'s, and its `doc/ssh.md` says how.

## Who signs for a task

A project's task is certified by the key of one of the project's members, a developer as well as an admin. See
[a task's own key](commands.md#task).

## The commands

[The commands](commands.md) name, for each write, whose it is. They act on these files, and every one of them signs with your own key
through your agent, so a command that is not yours is refused by the rule above even if the command let it through.
