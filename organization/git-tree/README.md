# organization/git-tree

The organization of the new structure, kept in one Git repository: `owners.yml`, `people/`, each project's card, members
and address, and each host's card and `host.yml`. Its history is walked path by path against who may change each path, and
every question is answered from what that walk accepted. A trust record, owner-only in the state directory, lets the next
read go on from there and refuse an older state served again. [Who may change each path](../../doc/roles.md). Its organization reader never opens a project's repository. It also holds address parsing, forge directory queries and the bounded [Forgejo admin API](https://forgejo.org/docs/latest/user/api-usage/) client used by the [host program](../../host/README.md); local administration reaches only the forge account's Unix socket.
