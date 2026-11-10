package org.fuin.porelo.app;

import java.util.List;
import org.fuin.porelo.core.project.file.RepositorySettings.Asked;

/** How a repository's own settings are shown to a person, in the same words wherever asked. */
public final class RepositorySettingsText {

    private RepositorySettingsText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says that a start does not run under settings this machine has not allowed.
     *
     * @param repository The repository's name.
     * @param asked What its files ask for.
     * @return One line, without the settings themselves.
     */
    public static String notAllowed(final String repository, final List<Asked> asked) {
        return "the porelo.yml of "
                + repository
                + " asks for "
                + asked.size()
                + (asked.size() == 1 ? " setting" : " settings")
                + " this machine has not allowed: "
                + String.join("; ", asked.stream().map(RepositorySettingsText::line).toList())
                + ". Start it in a terminal to be asked, or allow them in the window";
    }

    /**
     * Says that a start does not build image lines this machine has not allowed for a project.
     *
     * @param origin The file that gives them, as a start names it.
     * @param project The project's name.
     * @param asked What of them is to be allowed.
     * @return One line, with each of them.
     */
    public static String imageNotAllowed(
            final String origin, final String project, final List<Asked> asked) {
        return "the "
                + origin
                + " adds to the image of "
                + project
                + " what this machine has not allowed: "
                + String.join("; ", asked.stream().map(RepositorySettingsText::line).toList())
                + ". Start it in a terminal to be asked, or allow them in the window";
    }

    /**
     * Returns one setting as a person reads it.
     *
     * @param asked The setting.
     * @return {@code + key = value}, {@code ~ key = value (was ...)} or {@code - key (was ...)}.
     */
    public static String line(final Asked asked) {
        if (asked.removed()) {
            return "- " + asked.key() + " (was " + asked.was() + ")";
        }
        if (asked.was().isEmpty()) {
            return "+ " + asked.key() + " = " + asked.value();
        }
        return "~ " + asked.key() + " = " + asked.value() + " (was " + asked.was() + ")";
    }
}
