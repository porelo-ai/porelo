# What each agent can and cannot do

Porelo runs five agents, each as its own package. They do not all behave alike, and some limits hold harder than
others. This page says what each one does inside a task, at the version named at the top of its column.

**How to read a cell.** **S** means a scenario in the agent's acceptance suite covers it. **M** means somebody ran it by
hand and saw it, often once and on the CLI alone rather than through Porelo, at the column's version unless the cell
names an older one. **R** means it comes from the agent's definition, help or source, with no run recorded here. A later
version of an agent can change any of them. The agent's own repository holds the runs.

**A soft bound is a limit the agent could get around.** Every agent in a task has a shell. Porelo decides what leaves
the container, but a setting inside the container does not stop the agent reaching around it: it can start another copy
of its own CLI from that shell, and read any file it can see. A setting below that says "off" means the agent's own
feature is off, not that this is impossible. What holds whatever the agent does is on
[what a task can reach](reach.md).

| | [Claude Code](https://github.com/porelo-ai/porelo-claude-code) | [Oh My Pi](https://github.com/porelo-ai/porelo-omp) | [Pi](https://github.com/porelo-ai/porelo-pi) | [Codex CLI](https://github.com/porelo-ai/porelo-codex) | [Antigravity CLI](https://github.com/porelo-ai/porelo-agy) |
|---|---|---|---|---|---|
| Name in `--agent` | `claude` | `omp` | `pi` | `codex` | `agy` |
| Version | 2.1.311 | 18.14.2 | 1.4.0 | 0.163.0 | 1.4.5 |

## Signing in

These come from each agent's definition. Its own page says which of them have been run.

| | Claude Code | Oh My Pi | Pi | Codex CLI | Antigravity CLI |
|---|---|---|---|---|---|
| Default provider | `anthropic` | `openrouter` | `openrouter` | `chatgpt` | `google-sign-in` |
| Signing in with a subscription | Claude, by a pasted code | GitHub Copilot, by a device code, not on an Enterprise host | GitHub Copilot, by a device code | ChatGPT, by a device code | Google, by a pasted code |
| Using an API key | Anthropic, or OpenRouter | OpenRouter | OpenRouter | OpenAI, with `--provider openai` | Google, with the `google` provider |

## Running a task

| | Claude Code | Oh My Pi | Pi | Codex CLI | Antigravity CLI |
|---|---|---|---|---|---|
| Porelo's guide to the task (R) | Appended to the system prompt | Appended to the system prompt | Appended to the system prompt | **Not handed over:** the definition names no way | Linked as its `GEMINI.md` |
| Limit on turns when unattended (R) | Yes, `--max-turns` | No | No | No | No |
| Continuing a session after a stop | Yes (S) | Yes (S). **Stopped within seconds of a first answer, it may have none to continue** (M, 18.11.0) | Yes (S) | Yes (M) | Yes (R) |
| Woken at rest by a message | Yes (S) | Yes (S) | **No:** it finds a message when it looks, since nothing on its screen tells waiting from done (S) | Yes (S) | Yes (S) |
| Porelo can tell it is waiting for you | Yes, on its screen (M, 2.1.267) | Yes, on its screen (M, 18.4.1); not at its model picker or `/login` | **No:** Porelo says it cannot say (M, 0.85.0) | For workspace trust and a selection (R) and the model picker (M); not shown for every tool question | For workspace trust and a selection (R) |

## Subagents, workflows and skills

A task allows no subagents unless `limits.subagents` or `--subagents` says otherwise, as
[the project file](project-file.md#limits) describes. Porelo's aim is no skills and no workflows in a task at any number;
the rows below say how far each agent gets there, and Antigravity CLI does not.

| | Claude Code | Oh My Pi | Pi | Codex CLI | Antigravity CLI |
|---|---|---|---|---|---|
| No subagent allowed, the default | Off: its tools are denied (S) | Off: its tool is gone (S), and `agent()` in eval refused (M) | None seen to start, and nothing to switch off (M) | Off: a hook blocks the start (S) | Off: a hook denies the tools (S) |
| What shows that in a scenario | The tool calls and the session's record | The tool calls | **No scenario** | The count of session files, **and no workflow runs it** | **Only what the model answers** |
| Subagents allowed | At 1, of three asked at once one ran and two were refused (M); no higher number run | At 1, none was refused, and each waited as the agent reports it (M, 18.12.1); no higher number run | **Refused** by Porelo with exit code 64 above 0 (R) | At 1, of three asked at once two were refused (M); no higher number run, and whether it counts a subagent's own is open | **Refused** by Porelo with exit code 64 above 0 (M) |
| A subagent starting its own | Porelo sets a depth of 1, which holds on the CLI (M); not run again through the adapter | Porelo sets a depth of 1 (R); not run through the adapter | Does not apply | **Not stopped:** no setting measured holds (M) | Does not apply |
| Workflows | Off: the tool is denied (M) | Not looked for by name; asked for a second way to a subagent, it found none (M) | None named in its help (R) | Not looked for by name; asked for a second way to a subagent, it found none (M) | Not offered unattended (M), and the hook names it; not asked at a terminal |
| Skills | Off: the tool is denied (M) | Off: `skills.enabled: false` (M, one run, no scenario) | Off: `--no-skills` (M, one run, no scenario) | **Hidden only:** left out of its instructions (M, one run, no scenario) | **Not off:** nothing measured switches them off or hides them (M) |
| Reading a skill's file anyway | Possible | Possible | Possible | Possible | Possible, and it is told of them |
| Another way to a model | `claude -p` from its shell (M) | Its shell (R); `completion()` and `judge()` in eval (M) | Another `pi` from its shell (R) | Its shell (R) | Its shell (R); whether `schedule` starts an agent later is open (M) |

## What is not on this page

The hosts an agent needs and those it is refused are in `porelo agents --verbose`, for the version installed. Whether a
repository can bring its own hooks, extensions or settings into a task has not been compared across the five.
