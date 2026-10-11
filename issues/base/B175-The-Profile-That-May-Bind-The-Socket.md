# B175 — The Profile That May Bind The Socket

**Status:** Idea.

**What must be true.** On a machine that confines programs by AppArmor rather than SELinux, only Sokar's binaries may
bind the sockets a task connects to, as the SELinux domain of [B40](README.md#b40) ensures on SELinux machines, so a
process of the same account that is not Sokar cannot stand in for the broker. Until it is built, that second lock holds
on SELinux machines only, and `doc/security.md` says so.
