# Account settings

Settings that belong to the account rather than to a project live in `$XDG_CONFIG_HOME/porelo/config.yml`. A missing
file is every default; an unknown key or a bad value stops `porelod` at its start, and any command that reads the
setting, naming the key.

```yaml
limits:
  services: 1.0
  machines: 1.0
  polling: 1.0
  retries: 1.0
task:
  certificate_days: 7
identity:
  card_days: 30
  renew_notice_days: 7
messages:
  asked_about_days: 7
records:
  kept_days: 30
```

| Key | Default | What it sets |
|---|---|---|
| `limits.services` | 1.0 | the factor for limits that wait for an answer from outside |
| `limits.machines` | 1.0 | the factor for limits that wait for a program, container or machine |
| `limits.polling` | 1.0 | the factor for how long until something is looked at again |
| `limits.retries` | 1.0 | the factor for how often and after what pause a failed step is tried again |
| `task.certificate_days` | 7 | how long the person's certification of a task holds; a host refuses more than its own `certificate_days` |
| `identity.card_days` | 30 | how long a card holds from the day it is made |
| `identity.renew_notice_days` | 7 | how many days before its end a card is said to be due |
| `messages.asked_about_days` | 7 | how long a sent message is asked about |
| `records.kept_days` | 30 | how long a task's run records are kept |

- **A factor is a decimal from 0.1 to 100** with at most two digits after its dot; [Time limits](limits.md) says
  what each one scales and how it travels to every program and client.
- **A number of days is whole, from 1 to 3650**, and is the person's decision: no factor multiplies it. A task
  started on a host is certified for these days only up to that host's own [`certificate_days`](#the-hosts-settings);
  a longer certificate is refused there.
- **A number is written as a plain decimal**, such as `30` or `1.5`: YAML's other forms, such as `010`, `0x1E`, `1_0`
  or `1:30`, are refused by their key, as is a key written twice.

## The host's settings

A host that porelo-host runs on has settings of its own in `/etc/porelo-host/host.yml`, root's and readable by
everyone, which the host's Ansible collection writes from its inventory on every setup and removes when the host is
taken down. porelo-host reads it, and so does the host's check of a task certificate; a person's file never reaches
them.

```yaml
limits: services=1.0,machines=1.0,polling=1.0,retries=1.0
certificate_days: 7
lent_token_days: 1
```

| Key | Default | What it sets |
|---|---|---|
| `limits` | all four 1.0 | the host's four factors, as one [limits line](limits.md#the-limits-line) |
| `certificate_days` | 7 | the longest a task certificate may hold on this host; a person's `task.certificate_days` above it is refused at a start here, and a task started uncertified holds as long |
| `lent_token_days` | 1 | how long porelo-host keeps a forge token it lent |

- **A key left out is its default**, and so is a missing file.
- **A number of days is whole, from 1 to 3650, written as a plain decimal.** Any other value, among them YAML's
  forms such as `010`, `0x1E`, `1_0` or `1:30`, an unknown key, a key written twice or a file that is no YAML mapping
  stops porelo-host with exit code 69, naming the file and the key, before it does anything.
