package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Builds what {@code commit verify} judges a project's push with, from the state a host took with
 * {@code org fetch}: only the keys in force, and a locked member's not at all.
 */
@Command(
        name = "signers",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Builds a project's allowed signers and certifiers from a host's state.")
public class OrgSignersCommand extends OrgReading {

    @Option(names = "--state", required = true, description = "The state org fetch answered.")
    private Path state = Path.of("");

    @Option(names = "--project", required = true, description = "The project.")
    private String project = "";

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final Map<?, ?> taken = read(state);
        final Map<?, ?> held = project(taken);
        final Set<String> locked = new HashSet<>();
        for (final Object each : list(held, "members")) {
            if (each instanceof Map<?, ?> member && Boolean.TRUE.equals(member.get("locked"))) {
                locked.add(String.valueOf(member.get("login")));
            }
        }
        final StringBuilder signers = new StringBuilder();
        final StringBuilder certifiers = new StringBuilder();
        for (final Object each : list(held, "signing_keys")) {
            if (!(each instanceof Map<?, ?> key)
                    || key.get("revoked_since") != null
                    || locked.contains(String.valueOf(key.get("login")))) {
                continue;
            }
            final String validity = validity(key);
            signers.append(key.get("login"))
                    .append(" namespaces=\"git\",")
                    .append(validity)
                    .append(' ')
                    .append(key.get("key"))
                    .append('\n');
            certifiers
                    .append("task:")
                    .append(project)
                    .append(":* cert-authority,namespaces=\"git\",")
                    .append(validity)
                    .append(' ')
                    .append(key.get("key"))
                    .append('\n');
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("project", project);
        answer.put("revision", taken.get("revision"));
        answer.put("signers", signers.toString());
        answer.put("certifiers", certifiers.toString());
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        String.valueOf(answer.get("signers")).lines().forEach(this::line);
    }

    /** A card's dates in the form ssh-keygen reads, so a key ends when its card says. */
    private static String validity(final Map<?, ?> key) {
        return "valid-after=\""
                + day(key.get("valid_from"))
                + "\",valid-before=\""
                + day(key.get("valid_until"))
                + "\"";
    }

    private static String day(final @Nullable Object date) {
        final String text = String.valueOf(date);
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw unreadable();
        }
        // In UTC, as a card's day is read everywhere: without the mark ssh-keygen takes local time.
        return text.replace("-", "") + "Z";
    }

    private static Map<?, ?> read(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refused(Refused.USAGE, "--state names no regular file", null);
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                return m;
            }
        } catch (RuntimeException ex) {
            throw unreadable();
        }
        throw unreadable();
    }

    private Map<?, ?> project(final Map<?, ?> taken) {
        for (final Object each : list(taken, "projects")) {
            if (each instanceof Map<?, ?> row && project.equals(row.get("project"))) {
                return row;
            }
        }
        throw new Refused(
                Refused.NOT_FOUND,
                "the state names no such project on this host",
                taken.get("revision") instanceof String revision ? revision : null);
    }

    private static List<?> list(final Map<?, ?> map, final String field) {
        if (map.get(field) instanceof List<?> values) {
            return values;
        }
        throw unreadable();
    }

    /** It is the host's own file, and what it held is not repeated. */
    private static Refused unreadable() {
        return new Refused(Refused.USAGE, "--state is no state org fetch answered", null);
    }
}
