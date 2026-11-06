# Who may do what in an organization

An organization is one Git repository. Write access to that repository says nothing: every reader walks its history and judges
each file a commit changes by the key that signed the commit. A change signed by the wrong key is refused on that path, and what the
reader answers stays as it was. This page says who may change what.

## The roles

| Role | Where it is written | What it answers for |
|---|---|---|
| Owner | `owners.yml` | Owners and organization admins. |
| Organization admin | `organization-admins.yml` | Cards, a project's card, machines, and the admins of projects and machines. |
| Machine admin | `hosts/<host>/host.yml`, `admins` | One machine: its card, what it carries, taking Porelo off it. |
| Project admin | `projects/<project>/members.yml`, role `admin` | One project: its developers, its repositories and their address, its `project.yml`. |
| Developer | `projects/<project>/members.yml`, role `developer` | Works in one project. |

The roles do not stack: nobody does another role's work unless listed for it too. An owner may list themselves as an organization
admin, and an organization admin who needs root on a machine lists themselves as its machine admin. A person holds a right only with
the key on their card, while that card is trusted and its key not revoked. A key whose card the forge has not yet vouched for neither
passes nor fails: the reader waits for the forge's word. A project admin who is locked holds no admin's right there.

## Before the list

The roles start with `organization-admins.yml`. While it is absent, an organization's owners do an organization admin's work, a
machine admin or project admin may also change that level's admins, and an owner moves no pin. `porelo org create` files it, naming
the founder, and `porelo org admin add` files it in an organization from before. Once it stands only the file counts, and it cannot be
removed. See [why](decisions.md#the-roles-start-with-their-list).

## The files

Each path is judged alone. A path not listed here is never read.

| File | Who may change it | What else holds |
|---|---|---|
| `owners.yml` | An owner | The first one names its signer, whom the forge must say owns the organization. It cannot be removed. An owner's backup key may only put one new key in place of the one it backs. |
| `organization-admins.yml` | An owner | A founding commit may list its founder alone. It may stand empty, and then nobody does that work until an owner lists someone. |
| `people/<login>.card` | The card's own key; an organization admin in place of a refused card or a lost key's | A change of key is handed on by the old key, or made by an organization admin or a backup that login filed, which revokes the old key. Removing a card, by its own key or an organization admin, revokes its key too. |
| `projects/<project>/card` | An organization admin | Adds the project with its first admin, names another, or removes the project, which also leaves its host. |
| `projects/<project>/members.yml` | A project admin for its developers, an organization admin for its admins | The card's admin writes the first one, naming themselves the one unlocked admin. It goes only with its project. |
| `projects/<project>/repository.yml` | A project admin | The project's address. It goes only with its project. |
| `hosts/<host>/host.yml` | An organization admin adds it, and changes its admins; a machine admin changes what it carries and their own backup key; either removes it | A new machine holds one admin and nothing else. An entry of `projects` is a project's name, the whole project, or `{name: P, repositories: [a, b]}`, some of its repositories. |
| `hosts/<host>/card` | A machine admin | An organization admin may remove it only together with `host.yml`. |

## A project's own file

`project.yml` does not stand in the organization. It stands in the project's own repository, which the organization's address names,
and only a machine reading that repository judges it: the newest commit that changed the file must be signed by the key of a project
admin who is one now and is not locked, within their card's days. Another signature there, or none, leaves the file refused until an
admin signs it anew with `porelo project sign`. See [how the file reaches a machine](project-file.md#how-the-file-reaches-a-machine).

A repository's `porelo.yml` may be signed anew by a project admin or developer with
`porelo project sign-repository-file PROJECT REPOSITORY`. The acting key must be valid today among the project's
keys taken on this machine. Without `--yes` the command shows the file; signing needs `--yes --base REVISION`.

## On a machine

A machine takes the organization at each follow, against a pin of keys it holds. A commit on its files must be signed for that pin, by
one of its admins or a listed organization admin, and the pin then follows both. So replacing a machine admin takes effect at the next
follow with no step of theirs. An owner not listed moves no pin: such a change leaves the machine at `refused-state`. One stolen
organization admin's key reaches every machine's pin, an accepted cost, and
[why](decisions.md#a-hosts-pin-follows-the-organization-admins-and-root-stays-with-its-machine-admins) says what it does not reach.

Root on a machine goes to its machine admins' keys alone. How a project's member comes to an account there is `porelo-host`'s, and its
`doc/ssh.md` says how.

## Who signs for a task

A project's task is certified by the key of one of the project's members, a developer as well as an admin. See
[a task's own key](commands.md#task).

## The commands

[The commands](commands.md) name, for each write, whose it is. They act on these files, and every one of them signs with your own key
through your agent, so a command that is not yours is refused by the rule above even if the command let it through.
