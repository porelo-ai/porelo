package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/** One allowed-signers line, keeping its options separate from the key. */
record AllowedSigner(String principal, String key, List<String> options) {

    private static final Pattern LINE =
            Pattern.compile(
                    "^(\\S+)\\s+(?:((?:[^\\s\"]|\"[^\"]*\")+)\\s+)?"
                            + "((?:ssh-|ecdsa-|sk-)[^\\s]+\\s+\\S+)(?:\\s+.*)?$");

    static @Nullable AllowedSigner parse(final String line) {
        final Matcher match = LINE.matcher(line.strip());
        if (!match.matches() || line.strip().startsWith("#")) {
            return null;
        }
        final List<String> options = new ArrayList<>();
        final String text = match.group(2);
        if (text != null) {
            boolean quoted = false;
            int start = 0;
            for (int at = 0; at < text.length(); at++) {
                if (text.charAt(at) == '"') {
                    quoted = !quoted;
                } else if (text.charAt(at) == ',' && !quoted) {
                    options.add(text.substring(start, at));
                    start = at + 1;
                }
            }
            options.add(text.substring(start));
        }
        return new AllowedSigner(
                match.group(1), match.group(3).replaceAll("\\s+", " "), List.copyOf(options));
    }

    static List<String> read(final CommandRunner runner, final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file);
        final List<String> entries = new ArrayList<>();
        for (final String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            final AllowedSigner parsed = parse(line);
            if (parsed == null) {
                throw new IOException("invalid allowed-signers line");
            }
            entries.add("porelo" + line.strip().substring(parsed.principal().length()));
        }
        if (entries.isEmpty()) {
            return lines;
        }
        final Path check = Files.createTempFile("porelo-signers-check-", "");
        try {
            Files.write(check, entries);
            final var result =
                    runner.run(
                            Command.of(
                                    "ssh-keygen",
                                    "-Y",
                                    "match-principals",
                                    "-I",
                                    "porelo",
                                    "-f",
                                    check.toString()));
            if (!result.successful() || !result.standardError().isBlank()) {
                throw new IOException(
                        "invalid allowed-signers file: " + result.standardError().strip());
            }
        } catch (final RuntimeException ex) {
            throw new IOException("cannot validate the pinned keys: " + ex.getMessage(), ex);
        } finally {
            Files.deleteIfExists(check);
        }
        return lines;
    }

    boolean retired() {
        return options.stream()
                .anyMatch(option -> option.toLowerCase(Locale.ROOT).startsWith("valid-before="));
    }
}
