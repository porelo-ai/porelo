package org.fuin.porelo.core.project;

import java.util.Locale;
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
 * @param issues The issue prefixes this repository owns, each one or more capital letters; empty
 *     when it has none.
 * @param agent The agent this repository is worked with, by the name {@code porelo agents} lists,
 *     or {@code null} when the project file names none. Whether it is installed is the start's
 *     question: a followed project is read on machines without it.
 * @param nickname The name its agent is addressed by, or {@code null} for none.
 * @param branch How a task's branch at its forge is named, or {@code null} for the project's.
 * @param review Whether a merge on the host's forge needs another person's approval: {@link
 *     #REVIEW_REQUIRED} or {@link #REVIEW_OPTIONAL}.
 * @param central Where a host takes the repository from, or {@code null} for nowhere.
 */
public record Repository(
        String name,
        @Nullable String upstream,
        String description,
        java.util.List<String> issues,
        @Nullable String agent,
        @Nullable String nickname,
        @Nullable BranchScheme branch,
        String review,
        @Nullable String central) {

    /** A merge needs another person's approval; what a file that says nothing means. */
    public static final String REVIEW_REQUIRED = "required";

    /** The author may merge their own. */
    public static final String REVIEW_OPTIONAL = "optional";

    /** What an agent's name looks like: lower-case letters, digits and hyphens. */
    public static final String AGENT_PATTERN = "[a-z0-9]+(?:-[a-z0-9]+)*";

    /**
     * What a nickname looks like: ASCII words separated by single spaces, a letter first, at most
     * 32 characters, so that {@code Agent } before it fits a transport's 64-character display name,
     * no look-alike letter passes for another agent, and nothing reads it as an address, a list's
     * separator or an option.
     */
    public static final String NICKNAME_PATTERN =
            "(?=.{1,32}$)[A-Za-z][A-Za-z0-9-]*(?: [A-Za-z0-9][A-Za-z0-9-]*)*";

    /** The nickname's rule in words, for every refusal of one. */
    public static final String NICKNAME_RULE =
            "a nickname is at most 32 ASCII letters, digits and hyphens in words separated by single"
                    + " spaces, a letter first; a colon, a comma, a control or non-ASCII character,"
                    + " a double space and a space at either end are refused";

    /** What the name an agent goes by starts with. */
    private static final String AGENT_PREFIX = "Agent ";

    /**
     * Returns this repository as a machine reaches it at another address.
     *
     * @param address Where it is reached.
     * @return The repository.
     */
    public Repository reachedAt(final String address) {
        return new Repository(
                name, address, description, issues, agent, nickname, branch, review, central);
    }

    /**
     * Constructor for a repository that says nothing of a review or of a central source.
     *
     * @param name Its name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is.
     * @param issues The issue prefixes it owns.
     * @param agent The agent it is worked with, or {@code null}.
     * @param nickname The name its agent is addressed by, or {@code null}.
     */
    public Repository(
            String name,
            @Nullable String upstream,
            String description,
            java.util.List<String> issues,
            @Nullable String agent,
            @Nullable String nickname) {
        this(name, upstream, description, issues, agent, nickname, null, REVIEW_REQUIRED, null);
    }

    /** What an issue prefix looks like: one or more capital letters. */
    public static final String ISSUES_PATTERN = "[A-Z]+";

    /**
     * Constructor for a repository that names nothing but where its work goes and what it is.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     */
    public Repository(String name, @Nullable String upstream, String description) {
        this(name, upstream, description, java.util.List.of(), null, null);
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
                            + "'; "
                            + NICKNAME_RULE);
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
     * Says whether an address is written in a form a registry or an access file takes: {@code
     * ssh://}, {@code https://} or {@code user@host:path}, and no remote helper's {@code
     * transport::}; the rule is {@link AddressForm#isRepository}.
     *
     * @param address The address.
     * @return Whether it has that form; whether git would read it as an option is asked by {@link
     *     #refuseAsOption}.
     */
    public static boolean isAddress(String address) {
        return AddressForm.isRepository(address);
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
        // The value as given, never a stripped copy: a trailing space or line break passed that
        // and stayed in the address git was handed.
        if (value.startsWith("-")
                || value.startsWith("ext::")
                || value.startsWith("fd::")
                || value.chars()
                        .anyMatch(
                                c ->
                                        Character.isWhitespace(c)
                                                || Character.isSpaceChar(c)
                                                || Character.isISOControl(c))) {
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
     * @return {@code Agent <nickname>}, or {@code Agent <Name>} without a nickname.
     */
    public String goesBy() {
        return goesBy(nickname, name);
    }

    /**
     * Returns the name an agent goes by: its nickname as written, or else the repository's or the
     * project's name with its first letter capitalized and the rest as it is.
     *
     * @param nickname The nickname, or {@code null} for none.
     * @param name The repository's key or the project's name.
     * @return {@code Agent <nickname>}, or {@code Agent <Name>}.
     */
    public static String goesBy(final @Nullable String nickname, final String name) {
        if (nickname != null) {
            return AGENT_PREFIX + nickname;
        }
        return AGENT_PREFIX
                + (name.isEmpty()
                        ? name
                        : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1));
    }

    /**
     * Returns a name as two names are compared: people tell neither case nor a run of spaces apart.
     *
     * @param goesBy A name an agent goes by.
     * @return It in lower case, with each run of spaces one space.
     */
    public static String compared(final String goesBy) {
        return goesBy.toLowerCase(Locale.ROOT).replaceAll(" +", " ");
    }

    /**
     * Tells whether approved work from this repository has anywhere to go.
     *
     * @return {@code true} when an upstream was named.
     */
    public boolean hasUpstream() {
        return upstream != null;
    }
}
