package org.fuin.porelo.core.project;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How a task's branch at the forge is named, and which names the forge accepts.
 *
 * <p>The template makes the name, the pattern judges it: a regular expression alone could check a
 * name but not make one. The pattern is also what the gate keeps out of the mirror an agent fetches
 * from, so another task's branch at the forge never reaches a task.
 *
 * @param template The name, with {@code {task}}, {@code {project}}, {@code {repository}} and the
 *     names of the task's values in braces, filled by {@link Parameters#fill}.
 * @param pattern The names the forge accepts, a regular expression matched against the whole name.
 */
public record BranchScheme(String template, String pattern) {

    /**
     * A placeholder, as {@link Parameters#fill} fills it. First, since the default below is checked
     * with it.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9][a-z0-9-]*)}");

    /** Today's name, for a project that says nothing. */
    public static final BranchScheme DEFAULT = new BranchScheme("porelo/{task}", "^porelo/.+$");

    /**
     * Constructor.
     *
     * @param template The name to make.
     * @param pattern The names the forge accepts.
     * @throws ProjectException If either is empty or the pattern does not compile, or the template
     *     holds a brace that is not a placeholder.
     */
    public BranchScheme {
        if (template.isBlank()) {
            throw new ProjectException("branch.template is empty");
        }
        if (pattern.isBlank()) {
            throw new ProjectException("branch.pattern is empty");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            throw new ProjectException(
                    "branch.pattern is not a regular expression: " + ex.getDescription());
        }
        if (PLACEHOLDER.matcher(template).replaceAll("").matches(".*[{}].*")) {
            throw new ProjectException(
                    "branch.template '" + template + "' holds a brace that is not a {placeholder}");
        }
    }

    /**
     * Says whether git takes a name for a branch, by the rules of {@code git check-ref-format}: no
     * part beginning with a dot or ending in {@code .lock}, no {@code ..}, no {@code @{}, no space,
     * control character or {@code ~ ^ : ? * [ \}, no empty part, no dot at the end and not {@code
     * @} alone. Checked here rather than by running git, so a dry run starts no process.
     *
     * @param name A branch name, without {@code refs/heads/}.
     * @return {@code true} when git takes it.
     */
    public static boolean refFormat(String name) {
        if (name.isEmpty()
                || "@".equals(name)
                || "HEAD".equals(name)
                || name.startsWith("-")
                || name.endsWith(".")
                || name.contains("..")
                || name.contains("@{")
                || name.contains("//")
                || name.startsWith("/")
                || name.endsWith("/")) {
            return false;
        }
        for (final char each : name.toCharArray()) {
            if (each < 0x20 || each == 0x7f || " ~^:?*[\\".indexOf(each) >= 0) {
                return false;
            }
        }
        for (final String part : name.split("/")) {
            if (part.startsWith(".") || part.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Says whether the forge accepts a branch name.
     *
     * @param name A branch name, without {@code refs/heads/}.
     * @return {@code true} when the pattern matches it whole.
     */
    public boolean accepts(String name) {
        return Pattern.compile(pattern).matcher(name).matches();
    }
}
