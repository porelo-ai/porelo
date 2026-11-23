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
host:
  lent_token_days: 1
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
| `task.certificate_days` | 7 | how long the person's certification of a task holds |
| `identity.card_days` | 30 | how long a card holds from the day it is made |
| `identity.renew_notice_days` | 7 | how many days before its end a card is said to be due |
| `host.lent_token_days` | 1 | how long a token lent to a host is kept |
| `messages.asked_about_days` | 7 | how long a sent message is asked about |
| `records.kept_days` | 30 | how long a task's run records are kept |

- **A factor is a decimal from 0.1 to 100** with at most two digits after its dot; [Time limits](limits.md) says
  what each one scales and how it travels to every program and client.
- **A number of days is whole, from 1 to 3650**, and is the person's decision: no factor multiplies it.
