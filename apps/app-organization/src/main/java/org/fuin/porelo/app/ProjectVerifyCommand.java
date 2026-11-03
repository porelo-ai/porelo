package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Checks a project's file in a copy of its repository, as a host does at each follow: no network,
 * no home, no person. What it answers comes only from a file a key in force signed.
 */
@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Checks a project's file in a copy of its repository, against its signers.")
public class ProjectVerifyCommand extends OrgReading {

    private static final Pattern KEY =
            Pattern.compile("(?:^|\\s)((?:ssh|ecdsa|sk)-[a-z0-9@.-]+ [A-Za-z0-9+/=]+)(?:\\s|$)");

    private static final LocalDate EVER = LocalDate.of(1, 1, 1);

    private static final LocalDate NEVER = LocalDate.of(9999, 12, 31);

    @Option(
            names = "--repository",
            required = true,
            paramLabel = "GIT-DIR",
            description = "The copy of the project's repository, read and never written.")
    private Path repository = Path.of("");

    @Option(
            names = "--signers",
            required = true,
            paramLabel = "FILE",
            description = "The keys of the project's admins, in allowed signers format.")
    private Path signers = Path.of("");

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refused(Refused.USAGE, "--repository names no directory", null);
        }
        final ProjectFile.Read read =
                ProjectFile.standing(machine.runner(), repository, keys(signers));
        final Project project = read.project();
        if (!ProjectFile.READ.equals(read.state()) || project == null) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    read.why() == null ? "the project's file cannot be read" : read.why(),
                    null);
        }
        final Map<String, @Nullable Object> repositories = new LinkedHashMap<>();
        for (final Repository each : project.repositories()) {
            final Map<String, @Nullable Object> row = new LinkedHashMap<>();
            row.put("review", each.review());
            row.put("upstream", each.upstream());
            row.put("central", each.central());
            repositories.put(each.name(), row);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("project", project.name());
        answer.put("revision", read.revision());
        answer.put("project_review", project.review());
        answer.put("repositories", repositories);
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line("Project: " + answer.get("project") + " at " + answer.get("revision"));
    }

    /** Reads the signers, whose lines are not printed: a file named wrongly may hold anything. */
    private static List<SigningKey> keys(final Path file) throws IOException {
        OrgFetchCommand.regular(file, "--signers");
        final List<SigningKey> keys = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String kept = line.strip();
            if (kept.isEmpty() || kept.startsWith("#")) {
                continue;
            }
            final Matcher key = KEY.matcher(kept);
            if (!key.find()) {
                throw notSigners();
            }
            keys.add(
                    new SigningKey(
                            kept.split("\\s", 2)[0],
                            "admin",
                            key.group(1),
                            day(kept, "valid-after", EVER),
                            day(kept, "valid-before", NEVER),
                            null));
        }
        if (keys.isEmpty()) {
            throw new Refused(Refused.USAGE, "--signers holds no key", null);
        }
        return keys;
    }

    private static LocalDate day(final String line, final String option, final LocalDate none) {
        final Matcher found = Pattern.compile(option + "=\"?([0-9]{8})").matcher(line);
        if (!found.find()) {
            if (line.contains(option + "=")) {
                throw notSigners();
            }
            return none;
        }
        try {
            return LocalDate.parse(found.group(1), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException ex) {
            throw notSigners();
        }
    }

    private static Refused notSigners() {
        return new Refused(Refused.USAGE, "--signers holds a line that is no signer's", null);
    }
}
