package org.fuin.porelo.core.project;

import java.util.List;

/**
 * Every key a project file may hold, described for an editor a person new to Porelo can fill.
 *
 * <p>Which keys there are is the file model's to say ({@link
 * org.fuin.porelo.core.project.file.ProjectFileReader#sections()}), and a test holds the two to the
 * same set. Two places are open by design: the names under {@code credentials} and {@code
 * repositories} are the project's own, and what is under {@code mail.transports.<scheme>} is the
 * transport's, handed to it as it is written - the transport refuses what it does not know. A block
 * under {@code extensions} is likewise its ecosystem adapter's.
 */
final class ProjectSchema {

    /** The modes a message rule takes, in the order they are offered. */
    private static final List<String> MODES = List.of(Mail.PROMPT, Mail.ALLOW, Mail.DENY, Mail.OFF);

    /** How far a repository can be built on, from just hatched to kept only for those on it. */
    static final List<String> MATURITIES =
            List.of("crazy-chicken", "early-bird", "stable", "deprecated");

    private static final List<ProjectKey> KEYS =
            List.of(
                    key(
                            "",
                            "project",
                            "Who the project is and how far its agents are trusted.",
                            "map",
                            "",
                            true),
                    key("", "image", "What a task's container is built from.", "map", "", true),
                    key(
                            "",
                            "egress",
                            "What a task may reach beyond what its agent needs; nothing else resolves at all.",
                            "map",
                            "",
                            false),
                    key(
                            "",
                            "base_branch",
                            "Starting branch when no caller checkout or explicit --from is given.",
                            "string",
                            "",
                            false),
                    key("", "limits", "What one task may consume.", "map", "", false),
                    key(
                            "",
                            "branch",
                            "How a task's branch at the forge is named and which names the forge"
                                    + " accepts; without it, porelo/<task>.",
                            "map",
                            "",
                            false),
                    key(
                            "branch",
                            "template",
                            "The branch name, with {task}, {project}, {repository} and the task's"
                                    + " values in braces; needs a pattern.",
                            "string",
                            "porelo/{task}",
                            false),
                    key(
                            "branch",
                            "pattern",
                            "A regular expression of the branch names the forge accepts; the gate"
                                    + " keeps every branch it matches from the tasks.",
                            "string",
                            "^porelo/.+$",
                            false),
                    key(
                            "",
                            "mail",
                            "Whom the project's tasks may write to, and over which transports.",
                            "map",
                            "",
                            false),
                    key(
                            "",
                            "repositories",
                            "The other repositories the work happens in, each by the name a task start"
                                    + " takes; the project's own is always there.",
                            "map",
                            "",
                            false),
                    key(
                            "",
                            "credentials",
                            "Vault entries every task holds, each named with the destination it is for;"
                                    + " only names, never values.",
                            "map",
                            "",
                            false),
                    key(
                            "",
                            "parameters",
                            "The values a task of the project may be started with, by name; not secrets.",
                            "map",
                            "",
                            false),
                    key(
                            "parameters.<parameter>",
                            "description",
                            "For people: what the value is for.",
                            "string",
                            "",
                            false),
                    key(
                            "parameters.<parameter>",
                            "values",
                            "The values allowed; not with pattern.",
                            "list",
                            "",
                            false),
                    key(
                            "parameters.<parameter>",
                            "pattern",
                            "A regular expression the whole value must match; not with values.",
                            "string",
                            "",
                            false),
                    key(
                            "parameters.<parameter>",
                            "default",
                            "The value when a start gives none.",
                            "string",
                            "",
                            false),
                    key(
                            "parameters.<parameter>",
                            "required",
                            "Whether a start without a value is refused.",
                            "boolean",
                            "false",
                            false),
                    key(
                            "parameters.<parameter>",
                            "env",
                            "The variable the task sees it in, and a shell is read from;"
                                    + " PORELO_PARAM_<NAME> by default, none keeps it out of the task.",
                            "string",
                            "",
                            false),
                    key(
                            "",
                            "builds",
                            "An online project's tasks learn what the build of their own push did.",
                            "map",
                            "",
                            false),
                    key(
                            "builds",
                            "forge",
                            "Which forge runs the build, by the name its installed build reader declares.",
                            "string",
                            "",
                            true),
                    key(
                            "builds",
                            "credential",
                            "The vault entry holding the forge token; it never enters a task.",
                            "string",
                            "",
                            false),
                    key(
                            "builds",
                            "api",
                            "The forge API's https:// address, or http:// on this machine's loopback"
                                    + " (127.0.0.1, ::1, localhost) without user information; unset is"
                                    + " the forge's own public one.",
                            "string",
                            "",
                            false),
                    new ProjectKey(
                            "builds",
                            "logs",
                            "Which jobs' logs reach the task: 'failure', each failed one, or 'all',"
                                    + " every one once the build is finished.",
                            "string",
                            List.of(Builds.FAILURE, Builds.ALL),
                            Builds.FAILURE,
                            false),
                    key(
                            "project",
                            "short",
                            "Optional container spelling: lowercase letters, digits and hyphens, starting with a letter; at most 16 characters and no porelo- prefix.",
                            "string",
                            "",
                            false),
                    key(
                            "project",
                            "name",
                            "The project's name: lower-case letters, digits and hyphens, at most 63"
                                    + " characters, and not 'default'.",
                            "string",
                            "",
                            true),
                    key(
                            "project",
                            "description",
                            "For people: shown wherever the project is listed.",
                            "string",
                            "",
                            false),
                    new ProjectKey(
                            "project",
                            "security_class",
                            "How far the agent is trusted: offline reaches only declared host services,"
                                    + " guarded pushes to Porelo's gate and holds no credential for the upstream, online pushes to"
                                    + " the upstream itself.",
                            "string",
                            List.of("offline", "guarded", "online"),
                            "",
                            true),
                    key(
                            "project",
                            "central",
                            "The project's own repository at its central forge, where approved"
                                    + " work on it goes; required for online.",
                            "string",
                            "",
                            false),
                    key(
                            "project",
                            "upstream",
                            "The old name of 'central', read alike; rename it to 'central'.",
                            "string",
                            "",
                            false),
                    key(
                            "project",
                            "review",
                            "Whether a merge in the project's own repository needs another"
                                    + " person's approval: required, the default, or optional.",
                            "string",
                            "",
                            false),
                    key(
                            "project",
                            "nickname",
                            "The name the agent of the project's own repository goes by, as"
                                    + " Agent <nickname>, with the rules of a repository's"
                                    + " nickname; without one, Agent and the project's name with"
                                    + " its first letter capitalized.",
                            "string",
                            "",
                            false),
                    key(
                            "image",
                            "from",
                            "The image a task's container is built from; any with apt, apk or dnf.",
                            "string",
                            "",
                            true),
                    key(
                            "image",
                            "env",
                            "Variables set in the image, each a name and its value; a repository's"
                                    + " porelo.yml names an image of its own, taken whole.",
                            "map",
                            "",
                            false),
                    key(
                            "image",
                            "run",
                            "Extra Containerfile lines, run as root while the image is built: RUN,"
                                    + " ARG and ENV, and COPY only from an image pinned by"
                                    + " its digest.",
                            "string",
                            "",
                            false),
                    key(
                            "image",
                            "run_file",
                            "The same as run, in a file of the repository; not both.",
                            "string",
                            "",
                            false),
                    key("", "build", "What the image build itself fetches from.", "map", "", false),
                    key(
                            "build",
                            "package_sources",
                            "Where apt fetches from while the image is built.",
                            "list",
                            "[\"http://azure.archive.ubuntu.com/ubuntu/\"]",
                            false),
                    key(
                            "egress",
                            "sets",
                            "Curated sets of destinations by name; 'porelo shield sets' lists this machine's.",
                            "list",
                            "[]",
                            false),
                    key(
                            "egress",
                            "domains",
                            "Any other name a task may reach, on ports 80 and 443.",
                            "list",
                            "[]",
                            false),
                    key(
                            "egress",
                            "host_services",
                            "Names of services on this host, on TCP 443, including offline.",
                            "list",
                            "[]",
                            false),
                    key(
                            "egress",
                            "refused",
                            "Names that must not resolve, whatever allows them.",
                            "list",
                            "[]",
                            false),
                    key(
                            "limits",
                            "memory",
                            "The most memory a task may use, as podman takes it; \"none\" opts out.",
                            "string",
                            "8g",
                            false),
                    key(
                            "limits",
                            "pids",
                            "The most processes a task may run, which is what stops a fork bomb.",
                            "integer",
                            "2048",
                            false),
                    key(
                            "limits",
                            "cpus",
                            "The most CPUs a task may use, as podman takes it; \"none\" opts out.",
                            "string",
                            Limits.DEFAULT_CPUS,
                            false),
                    key(
                            "limits",
                            "hand_in",
                            "The largest file 'porelo task give' may hand to a running task, such as 64m;"
                                    + " fixed when the task starts.",
                            "string",
                            "64m",
                            false),
                    key(
                            "limits",
                            "subagents",
                            "The most subagents a task's agent may run at the same time; 0 for none."
                                    + " '--subagents' wins; an agent that cannot enforce it is refused.",
                            "integer",
                            "0",
                            false),
                    key(
                            "mail",
                            "peers",
                            "The names tasks may write to, each with where it is reached.",
                            "map",
                            "",
                            false),
                    new ProjectKey(
                            "mail",
                            "outgoing_filter",
                            "Whether the filter refuses what it finds in a task's outgoing"
                                    + " message, the default, or only reports it; a person can still read a refused message and deliver"
                                    + " it.",
                            "string",
                            List.of("blocking", "reporting"),
                            "blocking",
                            false),
                    key(
                            "mail",
                            Mail.RULES,
                            "How closely a message is watched, set once for every task: a mode for the"
                                    + " project's own tasks, for its conversation and for everyone else.",
                            "map",
                            "",
                            false),
                    new ProjectKey(
                            "mail.rules",
                            "project",
                            "The mode for a message to another task of the project.",
                            "string",
                            MODES,
                            Mail.ALLOW,
                            false),
                    new ProjectKey(
                            "mail.rules",
                            "room",
                            "The mode for a message to the project's conversation: allow where"
                                    + " unread work may leave this machine, prompt otherwise.",
                            "string",
                            MODES,
                            "",
                            false),
                    new ProjectKey(
                            "mail.rules",
                            "others",
                            "The mode for a message to any other peer.",
                            "string",
                            MODES,
                            Mail.DENY,
                            false),
                    key(
                            "mail",
                            "transports",
                            "Each transport's settings, by its scheme; the transport says what they"
                                    + " mean.",
                            "map",
                            "",
                            false),
                    key(
                            "mail.peers.<peer>",
                            "address",
                            "Where the peer is reached: <transport>:<the transport's own"
                                    + " address>.",
                            "string",
                            "",
                            true),
                    new ProjectKey(
                            "mail.peers.<peer>",
                            "trust",
                            "Whether what arrives from the peer is checked again here:"
                                    + " vouched for one of your own machines, external for anybody else.",
                            "string",
                            List.of(Mail.Peer.VOUCHED, Mail.Peer.EXTERNAL),
                            Mail.Peer.EXTERNAL,
                            false),
                    key(
                            "mail.peers.<peer>",
                            "per_day",
                            "How many messages a day a task may exchange with the peer, each"
                                    + " way.",
                            "integer",
                            String.valueOf(Mail.Peer.DEFAULT_PER_DAY),
                            false),
                    new ProjectKey(
                            "mail.peers.<peer>",
                            "mode",
                            "The mode for a message to this peer, over its class's" + " rule.",
                            "string",
                            MODES,
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "review",
                            "Whether a merge on the host's forge needs another person's"
                                    + " approval: required, the default, or optional.",
                            "string",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "central",
                            "The repository at its central forge: where approved work on it goes,"
                                    + " and where a host takes it from. Without one, work stays on"
                                    + " this machine.",
                            "string",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "upstream",
                            "The old name of 'central', read alike for where approved work"
                                    + " goes; rename it to 'central'.",
                            "string",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "description",
                            "For people: shown wherever the repository is listed.",
                            "string",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "issues",
                            "The issue prefixes this repository owns, each one or more"
                                    + " capital letters and named by no other repository; a task on it is told them.",
                            "list",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "branch",
                            "How a task's branch at this repository's forge is named, replacing the"
                                    + " project's; template and pattern as in 'branch'.",
                            "map",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "agent",
                            "The agent this repository is worked with, as 'porelo agents' lists it;"
                                    + " a task start without --agent takes it.",
                            "string",
                            "",
                            false),
                    key(
                            "repositories.<repository>",
                            "nickname",
                            "The name its agent goes by, as Agent <nickname>, in a conversation"
                                    + " and on its commits: words of ASCII letters, digits and"
                                    + " hyphens separated by single spaces, a letter first, at"
                                    + " most 32 characters; without one, Agent and the"
                                    + " repository's name with its first letter capitalized. No"
                                    + " two go by one name, whatever the case or the spaces, nor"
                                    + " one by another's followed by a space and more.",
                            "string",
                            "",
                            false),
                    new ProjectKey(
                            "repositories.<repository>",
                            "maturity",
                            "How far others can build on it: crazy-chicken, early-bird, stable or"
                                    + " deprecated; Porelo only checks the word.",
                            "string",
                            MATURITIES,
                            "",
                            false),
                    key(
                            "",
                            "mcp",
                            "Allows each task of the project an MCP endpoint, through which it starts,"
                                    + " lists and stops tasks within these limits; without it a task"
                                    + " gets none.",
                            "map",
                            "",
                            false),
                    key(
                            "mcp",
                            "methods",
                            "The daemon's methods the endpoint carries, by name without the"
                                    + " interface, such as Start; one never offered to MCP, or one"
                                    + " the daemon lacks, is refused.",
                            "list",
                            "",
                            true),
                    key(
                            "mcp",
                            "agents",
                            "The agents a task started through it may use, each to the models it"
                                    + " may be asked for; an agent with nothing under it takes any"
                                    + " model, one with a list must be given one of them.",
                            "map",
                            "",
                            true),
                    key(
                            "mcp",
                            "tasks",
                            "The most tasks one task may have running through it at once.",
                            "integer",
                            String.valueOf(McpEndpoint.DEFAULT_TASKS),
                            false),
                    key(
                            "mcp",
                            "minutes",
                            "The longest run a task started through it may be given, in minutes.",
                            "integer",
                            String.valueOf(McpEndpoint.DEFAULT_MINUTES),
                            false),
                    key(
                            "",
                            "providers",
                            "The model a task runs on for each provider, by the name 'porelo"
                                    + " providers' lists, unless its start names another; without"
                                    + " one the agent's own default.",
                            "map",
                            "",
                            false),
                    key(
                            "providers.<provider>",
                            "model",
                            "The model's id, one word, as the provider names it; a repository's"
                                    + " porelo.yml may name its own.",
                            "string",
                            "",
                            true),
                    key(
                            "providers.<provider>",
                            "label",
                            "What a person is shown for the model, one line.",
                            "string",
                            "",
                            false),
                    key(
                            "",
                            "tool_repositories",
                            "The repositories a build tool in a task reaches, each written into the"
                                    + " tool's settings by its ecosystem adapter at the start; the"
                                    + " egress names each host. An offline project declares none.",
                            "list",
                            "",
                            false),
                    key(
                            "tool_repositories[]",
                            "ecosystem",
                            "The adapter that writes the tool's settings, as 'porelo ecosystems'"
                                    + " lists it.",
                            "string",
                            "",
                            true),
                    key(
                            "tool_repositories[]",
                            "name",
                            "The repository's name: letters, digits, '_' and '-', each name once.",
                            "string",
                            "",
                            true),
                    key(
                            "tool_repositories[]",
                            "url",
                            "Its https URL, as a task reaches it; plain http only on 127.0.0.1 or"
                                    + " [::1].",
                            "string",
                            "",
                            true),
                    key(
                            "",
                            "extensions",
                            "Settings for one ecosystem adapter alone, by its name, which Porelo"
                                    + " passes to it unread; they widen nothing a task reaches.",
                            "map",
                            "",
                            false));

    private ProjectSchema() {}

    private static ProjectKey key(
            String section,
            String name,
            String describe,
            String type,
            String defaultValue,
            boolean required) {
        return new ProjectKey(section, name, describe, type, List.of(), defaultValue, required);
    }

    /**
     * Returns every key described, in the order a first project file is written in.
     *
     * @return The keys.
     */
    static List<ProjectKey> keys() {
        return KEYS;
    }

    /**
     * Returns a section as the keys here name it: a name the file chooses is {@code <repository>},
     * {@code <peer>} or {@code <parameter>}, where the file model says {@code <name>}.
     *
     * @param section The section as the file model names it.
     * @return It as the keys name it.
     */
    static String placeholder(String section) {
        return switch (section) {
            case "repositories.<name>" -> "repositories.<repository>";
            case "mail.peers.<name>" -> "mail.peers.<peer>";
            case "parameters.<name>" -> "parameters.<parameter>";
            case "providers.<name>" -> "providers.<provider>";
            default -> section;
        };
    }
}
