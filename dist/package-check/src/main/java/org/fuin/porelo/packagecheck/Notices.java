package org.fuin.porelo.packagecheck;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks the generated notices carried by one package.
 *
 * @param label how the report names the package
 * @param path where the package must carry the notices
 * @param jars the third-party runtime classpath's entry count
 */
record Notices(String label, String path, int jars) {

    // A coordinate inside license text is no section, and the runtime is not a jar.
    private static final Pattern SECTION =
            Pattern.compile(
                    "(?m)^={78}\\r?\\n([^\\s:]+:[^\\s:]+:[^\\s:]+(?::[^\\s:]+)?)\\r?\\nLicense: [^\\r\\n]+\\r?\\n={78}\\r?$");

    /** Checks the packaged text against the number of third-party jars the build supplied. */
    void check(@Nullable String text, Report report) {
        if (text == null || text.isBlank()) {
            report.fail(label + " ships no notices at " + path);
            return;
        }
        final long sections =
                SECTION.matcher(text).results().map(match -> match.group(1)).distinct().count();
        report.check(
                sections >= jars,
                label + " ships " + path + " with " + sections + " jar section(s)",
                label
                        + " ships "
                        + path
                        + " with "
                        + sections
                        + " jar section(s), but the build supplied "
                        + jars
                        + " third-party jar(s)");
    }

    /** Counts the entries of the third-party runtime classpath written by the build. */
    static int jarCount(String classpath) {
        final List<String> entries =
                Arrays.stream(classpath.split("[\\r\\n" + Pattern.quote(File.pathSeparator) + "]"))
                        .map(String::strip)
                        .filter(entry -> !entry.isEmpty())
                        .toList();
        if (entries.isEmpty() || entries.stream().anyMatch(entry -> !entry.endsWith(".jar"))) {
            throw new IllegalArgumentException("the third-party runtime classpath must name jars");
        }
        return entries.size();
    }
}
