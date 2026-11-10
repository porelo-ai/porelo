# B399 — A Maven Server Password Brought Into The Vault On Command

```json
{
  "$schema": "issue-1.json",
  "id": "B399",
  "title": "A Maven Server Password Brought Into The Vault On Command",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 38,
  "blockedBy": [],
  "covers": "A person brings one named Maven server's password from their own settings into the vault, at their explicit command"
}
```

**Why this size.** not estimated.

**What must be true.** A person can bring the password of one named Maven server from their own settings into the vault,
by an explicit command that names its id, such as a form of `vault import`. Nothing does it without being asked, and no
other server's password is read.

The build-tool plugin never reads the password: it names a server's id alone, and whether it is known
([B220](README.md#b220)). So this belongs to a command the person runs, not to the plugin.

## To be thought through

- Maven's encrypted values: a settings file may hold a password encrypted under a master password. Whether the command
  decrypts, refuses and says why, or asks for the value, is open.
- A stale password: what the vault holds must not fall behind the settings file unseen, and an old value must not land
  there unseen either. A start's check of a vault copy that fell behind (`reportStaleCredential`) is the model to read.
- A settings file that uses a value from the environment or a property instead of a literal.
