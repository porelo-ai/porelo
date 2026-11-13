package org.fuin.porelo.runtime;

import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ShortName;
import org.jspecify.annotations.Nullable;

/**
 * Names of the objects Porelo creates in podman.
 *
 * <p>Every name begins {@code porelo-} or, for project {@code porelo}'s tasks, {@code porelo_}, so
 * that an operator looking at {@code podman ps} can tell what belongs to Porelo, and so that a
 * cleanup can never match somebody else's container. What a name of Porelo's looks like is decided
 * here alone: {@link #isPorelo}, {@link #isTask} and {@link #NAME_PATTERN}.
 */
public final class ContainerName {

    /** Prefix on every container Porelo creates but project {@code porelo}'s tasks. */
    public static final String PREFIX = "porelo-";

    /**
     * Prefix on project {@code porelo}'s tasks: the project's name and Porelo's are one word, said
     * once. No other name has it, and no name made before holds an underscore.
     */
    public static final String OWN_PREFIX = "porelo_";

    /** A container name of Porelo's, by either beginning, as a regular expression. */
    public static final String NAME_PATTERN = "porelo[-_][a-z0-9][a-z0-9_-]*";

    /** Prefix on the throwaway containers an agent login runs in. */
    public static final String LOGIN_PREFIX = PREFIX + "login-";

    private ContainerName() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the container name for one task.
     *
     * <p><strong>One container per project and task, with nothing unique appended.</strong> It used
     * to carry the pid of the process that launched it, so two invocations never shared a container
     * and nothing could map a task to one - which is why starting a task that existed built a
     * second one beside it instead. An operator lost a task that way on 2026-09-11, and three
     * containers named for one task were the evidence.
     *
     * <p>The cost is deliberate: a task cannot run twice at once. Two runs of one task share the
     * project's mirror and its gate, so what is lost is a thing that only appeared to work.
     *
     * @param project The project.
     * @param task Task name.
     * @return Container name.
     */
    public static String of(Project project, String task) {
        return contract(project.name(), task);
    }

    /**
     * Returns a task's name in a transport's contract, {@code porelo-<project>-<task>}: the form a
     * room reads a task's account by, whatever its container is named now.
     *
     * @param project The project's name.
     * @param task The task's own name.
     * @return The name.
     */
    public static String contract(String project, String task) {
        return PREFIX + project + "-" + task;
    }

    /** Encodes a new task, refusing a spelling whose sockets cannot fit for this uid. */
    public static String encoded(String project, String task, long uid) {
        return encoded(project, task, null, null, uid);
    }

    /** Encodes each supplied short spelling, retaining the logical names as the fallbacks. */
    public static String encoded(
            String project,
            String task,
            @Nullable String projectShort,
            @Nullable String taskShort,
            long uid) {
        ShortName.refusal(projectShort)
                .ifPresent(
                        reason -> {
                            throw new IllegalArgumentException("project.short: " + reason);
                        });
        ShortName.refusal(taskShort)
                .ifPresent(
                        reason -> {
                            throw new IllegalArgumentException("--short: " + reason);
                        });
        if (!project.matches(Project.NAME_PATTERN) || uid < 0 || uid > 4294967295L) {
            throw new IllegalArgumentException("Use a valid project name and unsigned 32-bit uid");
        }
        final java.util.Optional<String> invalid = refusal("", task);
        if (invalid.isPresent()) {
            throw new IllegalArgumentException(invalid.orElseThrow());
        }
        final String spelling = taskShort == null ? task : taskShort;
        final String prefix = encodedPrefix(project, projectShort);
        // The daemon endpoint is one byte longer than the SSH socket; neither may exceed 107.
        final int budget = 107 - (10 + Long.toString(uid).length() + 8 + 4 + 12);
        final int room = budget - prefix.length();
        if (spelling.length() > room) {
            throw new IllegalArgumentException(
                    "Choose a task name of at most "
                            + Math.max(0, room)
                            + " characters for project '"
                            + project
                            + "' so its sockets fit"
                            + (room <= 0 ? "; choose a shorter project name" : ""));
        }
        return prefix + spelling;
    }

    /** Checks a recorded encoded identity without applying a new task's socket budget. */
    public static boolean isEncoded(String project, String task, String container) {
        return isEncoded(project, task, null, null, container);
    }

    /** Verifies the actual name from its original short spellings, without a current uid budget. */
    public static boolean isEncoded(
            String project,
            String task,
            @Nullable String projectShort,
            @Nullable String taskShort,
            String container) {
        if (ShortName.refusal(projectShort).isPresent() || ShortName.refusal(taskShort).isPresent())
            return false;
        final String spelling = taskShort == null ? task : taskShort;
        // Project porelo's tasks were made as 'porelo-<task>' before, and keep that name.
        return container.equals(encodedPrefix(project, projectShort) + spelling)
                || project.equals(OWN_PROJECT) && container.equals(PREFIX + spelling);
    }

    /** The project whose tasks begin {@link #OWN_PREFIX}. */
    private static final String OWN_PROJECT = "porelo";

    private static String encodedPrefix(String project, @Nullable String projectShort) {
        return project.equals(OWN_PROJECT)
                ? OWN_PREFIX
                : PREFIX + (projectShort == null ? project : projectShort) + "_";
    }

    /**
     * The longest container name whose sockets still fit a unix socket path, for any uid.
     *
     * <p>A path holds 107 bytes, and a task's longest is {@code
     * /run/user/<uid>/porelo/<container>/ssh-agent.sock}: 10 + up to 10 + 8 + 64 + 15.
     */
    public static final int MAX_LENGTH = 64;

    /** A task name: the project names' alphabet, starting and ending with a letter or digit. */
    private static final java.util.regex.Pattern TASK =
            java.util.regex.Pattern.compile(Project.TASK_NAME_PATTERN);

    /**
     * Tells whether a task name is one a message names somebody else by: the room's people, or a
     * person's own note. A task of that name would have its every message held, or pass as a
     * person's.
     *
     * @param task Task name.
     * @return {@code true} when no task may have it.
     */
    public static boolean reserved(String task) {
        return Mail.PEOPLE.equals(task) || Mail.PERSON.equals(task);
    }

    /**
     * Returns why a task name cannot be used, or empty when it can.
     *
     * <p>Asked before anything is built or written. podman refuses a container name outside {@code
     * [a-zA-Z0-9][a-zA-Z0-9_.-]*}, but only when the container is created - after the image was
     * built and the policy, resolver and sidecar were written under a directory named for a task
     * that never came to exist. The task is also a git ref, and a loose ref is a file, so upper
     * case would make {@code Foo} and {@code foo} one ref on a case-insensitive filesystem. A name
     * of only digits is refused because that is the shape that tells a login container apart, and
     * one beginning {@value #PREFIX} because that is the shape of a container's name.
     *
     * @param project The project's name, or {@code ""} when it is not known: the length then goes
     *     unchecked.
     * @param task Task name.
     * @return A sentence saying what is wrong, or empty.
     */
    public static java.util.Optional<String> refusal(String project, String task) {
        if (!TASK.matcher(task).matches()) {
            final String suggested = suggestion(project, task);
            return java.util.Optional.of(
                    "'"
                            + task
                            + "' is not a task name: use lowercase"
                            + " letters, digits and hyphens, starting and ending with a letter or digit"
                            + (suggested == null ? "" : " - '" + suggested + "' would do"));
        }
        if (task.chars().allMatch(Character::isDigit)) {
            return java.util.Optional.of(
                    "'"
                            + task
                            + "' is not a task name: it needs a letter,"
                            + " because a name of only digits is what marks a login container");
        }
        if (reserved(task)) {
            return java.util.Optional.of(
                    "'"
                            + task
                            + "' is not a task name: a message names the room's people or a"
                            + " person by it");
        }
        if (task.startsWith(PREFIX)) {
            // A start takes 'porelo-<project>-<task>' for the task it names, so such a name would
            // be one task's on this machine and another's given to a host.
            return java.util.Optional.of(
                    "'"
                            + task
                            + "' is not a task name: '"
                            + PREFIX
                            + "' begins a container's name, never a task's");
        }
        final String container = PREFIX + project + "-" + task;
        if (!project.isEmpty() && container.length() > MAX_LENGTH) {
            return java.util.Optional.of(
                    "'"
                            + task
                            + "' is too long for project '"
                            + project
                            + "': '"
                            + container
                            + "' has "
                            + container.length()
                            + " characters, and a"
                            + " container name may have "
                            + MAX_LENGTH
                            + " so that its sockets fit a unix"
                            + " socket path"
                            + wayOut(project, task));
        }
        return java.util.Optional.empty();
    }

    /**
     * Returns the name suggested for one with characters no task may have: lowercased, cut to the
     * room beside the project, and only when every rule takes it - a name of only digits, the
     * room's people, a container's beginning or one too long would be refused in turn.
     *
     * @param project The project's name, or {@code ""} when it is not known.
     * @param task The name given.
     * @return The suggestion, or {@code null} when no fitting one exists.
     */
    private static @Nullable String suggestion(String project, String task) {
        String suggested =
                task.toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (!project.isEmpty() && suggested.length() > taskRoom(project)) {
            suggested = shortened(suggested, taskRoom(project));
        }
        return TASK.matcher(suggested).matches() && refusal(project, suggested).isEmpty()
                ? suggested
                : null;
    }

    /**
     * What a person whose names are too long does instead: a shorter task, or a shorter project.
     */
    private static String wayOut(String project, String task) {
        final int room = taskRoom(project);
        if (room <= 0) {
            return ": the project's name leaves no room for any task, so give it a shorter name in"
                    + " project.yml";
        }
        final String shorter = shortened(task, room);
        final String example =
                !shorter.isEmpty() && refusal(project, shorter).isEmpty()
                        ? ", as in 'porelo task start " + shorter + "'"
                        : "";
        return ": give a task name of at most " + room + " characters" + example;
    }

    /**
     * Returns how many characters a task name may have beside a project.
     *
     * @param project The project's name.
     * @return The room, zero or less when no task fits.
     */
    public static int taskRoom(String project) {
        return MAX_LENGTH - PREFIX.length() - project.length() - 1;
    }

    /**
     * Returns a name cut to a length, back to its last whole word where it has one, never ending in
     * a hyphen.
     *
     * @param name A name of letters, digits and hyphens.
     * @param room The most characters it may have.
     * @return The name, cut; empty when there is no room.
     */
    public static String shortened(String name, int room) {
        if (room <= 0) {
            return "";
        }
        String cut = name;
        if (name.length() > room) {
            cut = name.substring(0, room);
            final int word = cut.lastIndexOf('-');
            if (name.charAt(room) != '-' && word > 0) {
                cut = cut.substring(0, word);
            }
        }
        return cut.replaceAll("-+$", "");
    }

    /**
     * Returns the task a name given for one means, taking a container name as the task it names.
     *
     * <p>{@code task list} shows container names and every other task verb takes one, so {@code
     * porelo-utils4j-shell} is what somebody types to start {@code shell} again - and it used to
     * become a second task, {@code porelo-utils4j-porelo-utils4j-shell}, with an empty workspace,
     * while the stopped one kept the work.
     *
     * @param project The project's name.
     * @param given What was given as the task.
     * @return The task name: the part after {@code porelo-<project>-}, or what was given.
     */
    public static String taskFrom(String project, String given) {
        final String prefix = PREFIX + project + "-";
        return given.startsWith(prefix) && given.length() > prefix.length()
                ? given.substring(prefix.length())
                : given;
    }

    /**
     * Returns the task name inside a container name, when it holds one.
     *
     * <p>The interface is given this rather than deriving it: the rule relating the two belongs to
     * Porelo and has already changed once.
     *
     * @param project The project the container belongs to.
     * @param container Container name.
     * @return The task name, or {@code ""} when the name does not have this shape.
     */
    public static String taskIn(String project, String container) {
        final String prefix = PREFIX + project + "-";
        return container.startsWith(prefix) ? container.substring(prefix.length()) : "";
    }

    /**
     * Returns the container name for one agent login.
     *
     * <p>The millisecond is enough: a login is interactive, so two of them a millisecond apart
     * would need two people at the same terminal.
     *
     * @return Container name.
     */
    public static String login() {
        return LOGIN_PREFIX + System.currentTimeMillis();
    }

    /**
     * Tells whether a name belongs to Porelo.
     *
     * @param name Container name.
     * @return {@code true} if Porelo created it.
     */
    public static boolean isPorelo(String name) {
        return name.startsWith(PREFIX) || name.startsWith(OWN_PREFIX);
    }

    /**
     * Returns a name of Porelo's without its beginning, for a file named after it.
     *
     * @param name Container name.
     * @return What follows {@code porelo-} or {@code porelo_}, or the name when it has neither.
     */
    public static String unprefixed(String name) {
        return name.startsWith(PREFIX)
                ? name.substring(PREFIX.length())
                : name.startsWith(OWN_PREFIX) ? name.substring(OWN_PREFIX.length()) : name;
    }

    /**
     * Tells whether a name is one of Porelo's login containers.
     *
     * <p><strong>The prefix alone is not enough.</strong> {@code login} is a legal project name -
     * the name check allows any lowercase word - so a task in a project called that is named {@code
     * porelo-login-shell-25471} and would be taken for a login container: hidden from {@code task
     * list}, and refused by attach and stop. What separates them is that a login carries only a
     * timestamp where a task carries a task name and a run id, so the whole remainder being digits
     * is a shape no task name can take.
     *
     * @param name Container name.
     * @return {@code true} if it is a login container.
     */
    public static boolean isLogin(String name) {
        if (!name.startsWith(LOGIN_PREFIX)) {
            return false;
        }
        final String rest = name.substring(LOGIN_PREFIX.length());
        return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
    }

    /**
     * Tells whether a name is a task.
     *
     * <p><strong>Not every Porelo container is a task.</strong> {@code vault login} runs the
     * agent's own login in a throwaway container, which carries the prefix so that a cleanup can
     * find it and an operator reading {@code podman ps} can tell who made it - but it has no
     * workspace, no gate, no ruleset and no clearance, so everything a task offers would answer
     * nonsense for it. It was reported showing up in {@code porelo task list}, which is where this
     * split comes from.
     *
     * @param name Container name.
     * @return {@code true} if the name is a task container.
     */
    public static boolean isTask(String name) {
        return isPorelo(name) && !isLogin(name);
    }
}
