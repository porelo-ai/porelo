package org.fuin.porelo.core.project;

import org.jspecify.annotations.Nullable;

/**
 * One git repository a project's work happens in.
 *
 * <p>A project is a named unit of work over one or more repositories, and this is one of them. Its
 * own repository - where {@code project.yml}, the planning and the issues live - is a repository
 * like any other and is reached through {@link Project#ownRepository()}; the ones it names are what
 * its agents change.
 *
 * <p><strong>A task works on exactly one.</strong> The repositories of a project are separate
 * because the work is separable - if it were not, it would be one repository - and a task that
 * changed three of them at once would have to be paid for at the gate, where <em>"this task's work
 * is waiting for review"</em> would have three answers and a person could approve a third of it.
 * Coordination between repositories happens between tasks, by message, with a record of what was
 * said.
 *
 * <p>Each repository keeps its own mirror, its own gate and its own review branch, so nothing about
 * review changes when a project names a second one.
 *
 * @param name What a person names when starting a task, and what the mirror directory is called.
 *     Dots separate labels; each label starts and ends with a letter or digit.
 * @param upstream Where approved work goes, or {@code null}. Required by an {@link
 *     SecurityClass#ONLINE} project, where the agent's own remote is the upstream; for the other
 *     classes a repository without one is a repository whose work stays on this machine, which is a
 *     legitimate state rather than a broken one.
 * @param description What it is for, for people. May be empty.
 * @param egress What this repository's own tooling needs, <strong>added</strong> to the project's.
 *     Empty when it declared none. Additive because egress is a list of grants: adding is the only
 *     operation that makes sense, and a repository's declaration must never take away something the
 *     project granted - not least because allowing a blocked connection writes itself back into
 *     this block.
 * @param limits What this repository's work needs, <strong>replacing</strong> the project's key by
 *     key. Not additive, because a limit is one number with nothing to add it to, and two memory
 *     limits for one container is not something podman can be asked for. The rule follows the
 *     value.
 * @param issues The issue prefixes this repository owns, each one or more capital letters; empty
 *     when it has none.
 * @param image What the project file says this repository's image is built from, or {@code null}
 *     when it says nothing; a later source in the order gives what it leaves unset.
 * @param agent The agent this repository is worked with, by the name {@code porelo agents} lists,
 *     or {@code null} when the project file names none. Whether it is installed is the start's
 *     question: a followed project is read on machines without it.
 * @param nickname The one word its agent is addressed by, or {@code null} for none.
 */
public record Repository(
        String name,
        @Nullable String upstream,
        String description,
        Egress egress,
        Limits.Declared limits,
        java.util.List<String> issues,
        @Nullable ImageSource image,
        @Nullable String agent,
        @Nullable String nickname) {

    /** What an agent's name looks like: lower-case letters, digits and hyphens. */
    public static final String AGENT_PATTERN = "[a-z0-9]+(?:-[a-z0-9]+)*";

    /**
     * What a nickname looks like: one word, a letter first, at most 32 characters, so that {@code
     * Agent } before it fits a transport's 64-character display name and nothing reads it as an
     * address, a separator or an option.
     */
    public static final String NICKNAME_PATTERN = "[A-Za-z][A-Za-z0-9-]{0,31}";

    /** What the name an agent goes by starts with. */
    private static final String AGENT_PREFIX = "Agent ";

    /**
     * Constructor for a repository that names no agent.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     * @param egress Added to the project's.
     * @param limits Replacing the project's key by key.
     * @param issues The issue prefixes it owns.
     * @param image What its image is built from, or {@code null}.
     */
    public Repository(
            String name,
            @Nullable String upstream,
            String description,
            Egress egress,
            Limits.Declared limits,
            java.util.List<String> issues,
            @Nullable ImageSource image) {
        this(name, upstream, description, egress, limits, issues, image, null, null);
    }

    /**
     * Constructor for a repository whose image the project file does not name.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     * @param egress Added to the project's.
     * @param limits Replacing the project's key by key.
     * @param issues The issue prefixes it owns.
     */
    public Repository(
            String name,
            @Nullable String upstream,
            String description,
            Egress egress,
            Limits.Declared limits,
            java.util.List<String> issues) {
        this(name, upstream, description, egress, limits, issues, null);
    }

    /** What an issue prefix looks like: one or more capital letters. */
    public static final String ISSUES_PATTERN = "[A-Z]+";

    /**
     * Constructor for a repository that names no issue prefixes.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     * @param egress What its own tooling needs, added to the project's.
     * @param limits What its work needs, replacing the project's key by key.
     */
    public Repository(
            String name,
            @Nullable String upstream,
            String description,
            Egress egress,
            Limits.Declared limits) {
        this(name, upstream, description, egress, limits, java.util.List.of());
    }

    /**
     * Constructor for a repository that declares neither egress nor limits.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     */
    public Repository(String name, @Nullable String upstream, String description) {
        this(name, upstream, description, Egress.none(), Limits.Declared.none());
    }

    /**
     * What a repository name looks like, for a caller that names one before a repository exists.
     */
    public static final String NAME_PATTERN = "(?=.{1,63}$)[a-z0-9]+(?:(?:-+|\\.)[a-z0-9]+)*";

    /** A repository name that can safely reach a directory and an image name. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile(NAME_PATTERN);

    /**
     * Constructor without a description.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     */
    public Repository(String name, @Nullable String upstream) {
        this(name, upstream, "");
    }

    /**
     * Constructor with checks.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     */
    public Repository {
        egress = egress == null ? Egress.none() : egress;
        limits = limits == null ? Limits.Declared.none() : limits;
        if (name == null || !NAME.matcher(name).matches()) {
            // It becomes a mirror directory and part of an image's name, so it is refused here,
            // not where a path turns out to hold a slash or a dot-dot.
            throw new ProjectException(
                    "Invalid repository name '"
                            + name
                            + "', expected lower-case letters, digits, hyphens and dots"
                            + " between them, starting and ending with a letter or digit,"
                            + " at most 63 characters");
        }
        upstream = upstream == null || upstream.isBlank() ? null : upstream;
        if (upstream != null) {
            refuseAsOption(upstream, "upstream");
        }
        description = description == null ? "" : description;
        issues = issues == null ? java.util.List.of() : java.util.List.copyOf(issues);
        if (agent != null && !agent.matches(AGENT_PATTERN)) {
            throw new ProjectException(
                    "'repositories."
                            + name
                            + ".agent' is '"
                            + agent.replaceAll("\\p{Cntrl}", "?")
                            + "'; an agent is named as 'porelo agents' lists it: lower-case letters,"
                            + " digits and hyphens");
        }
        if (nickname != null && !nickname.matches(NICKNAME_PATTERN)) {
            throw new ProjectException(
                    "'repositories."
                            + name
                            + ".nickname' is '"
                            + nickname.replaceAll("\\p{Cntrl}", "?")
                            + "'; a nickname is one word of at most 32 letters, digits or hyphens,"
                            + " starting with a letter");
        }
        for (final String prefix : issues) {
            if (!prefix.matches(ISSUES_PATTERN)) {
                throw new ProjectException(
                        "'repositories."
                                + name
                                + ".issues' holds '"
                                + prefix
                                + "'; an issue prefix is one or more capital letters, such as 'B' or 'PI'");
            }
        }
    }

    /**
     * Refuses an address a program would read as something other than an address.
     *
     * <p>An upstream reaches {@code git fetch} as written: {@code --upload-pack=<command>} ran that
     * command on the host at the next task start, {@code ext::} and {@code fd::} run or attach one
     * by design, and a line break or a space makes one value two. What an address is - a URL,
     * {@code user@host:path}, a path - stays as it was.
     *
     * @param value The address.
     * @param key Its key, for the message.
     * @throws ProjectException If it would be read as an option or a command.
     */
    public static void refuseAsOption(String value, String key) {
        final String said = value.strip();
        if (said.startsWith("-")
                || said.startsWith("ext::")
                || said.startsWith("fd::")
                || said.chars()
                        .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new ProjectException(
                    "'"
                            + key
                            + "' is '"
                            + value.replaceAll("\\p{Cntrl}", "?")
                            + "', which git or podman would read as an option or a command, not an address");
        }
    }

    /**
     * Returns the name this repository's agent goes by where people and agents talk, and on its
     * commits.
     *
     * @return {@code Agent <nickname>}, or {@code Agent <name>} without a nickname.
     */
    public String goesBy() {
        return AGENT_PREFIX + (nickname == null ? name : nickname);
    }

    /**
     * Tells whether approved work from this repository has anywhere to go.
     *
     * @return {@code true} when an upstream was named.
     */
    public boolean hasUpstream() {
        return upstream != null;
    }

    /**
     * Tells whether this repository asks for anything of its own.
     *
     * @return {@code true} when it declared neither egress nor limits, and so runs exactly as the
     *     project says.
     */
    public boolean addsNothing() {
        return egress.isEmpty() && limits.isEmpty();
    }
}
