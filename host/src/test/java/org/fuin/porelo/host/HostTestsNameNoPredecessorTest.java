package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host tool's tests say what {@code porelo-host} does, in their own words.
 *
 * <p>A test, a comment or an assertion message that cites a test of the scripts the host tool
 * replaced, names one of those scripts or speaks of the language they were written in points at
 * code nobody can run or read beside it. Fixed paths and upgrade fixtures can still hold those
 * names in string literals; a comment, a name or an assertion's message may not.
 *
 * <p>The patterns are built from pieces, so this file's own source does not match them; the class's
 * own name is the one spelling the lookbehind lets through.
 */
class HostTestsNameNoPredecessorTest {

    /** A comment that pairs a test with one of the replaced scripts' tests. */
    private static final Pattern CITATION = Pattern.compile("^//\\s*" + "p" + "y:");

    /** The language of the replaced scripts as a word or inside a name, not the tool python3. */
    private static final Pattern LANGUAGE = Pattern.compile("Pyt" + "hon|(?i:\\bpyt" + "hon\\b)");

    /** The word the replaced scripts were called by, in any case; this class's name aside. */
    private static final Pattern SUCCESSION =
            Pattern.compile("(?i)(?<!HostTestsNameNo)prede" + "cessor");

    /** The replaced scripts by name, the pool's alike. */
    private static final Pattern SCRIPT =
            Pattern.compile(
                    "porelo-(host-(per"
                            + "son|pro"
                            + "ject|pu"
                            + "sh)|forge-ga"
                            + "te|po"
                            + "ol-)");

    /** The assertion calls whose string arguments are a message, never an expected output. */
    private static final Set<String> MESSAGE_CALLS =
            Set.of("as", "describedAs", "withFailMessage", "overridingErrorMessage");

    private static final Path TESTS = Path.of("src", "test", "java");

    @Test
    void noTestSourceCitesTheReplacedScripts() throws IOException {
        final List<Path> files = sources(TESTS);
        assertThat(files)
                .as("test sources found under %s", TESTS.toAbsolutePath())
                .contains(
                        TESTS.resolve("org/fuin/porelo/host/HostTestsNameNoPredecessorTest.java"),
                        TESTS.resolve("org/fuin/porelo/host/HostProjectTest.java"));
        final List<String> findings = new ArrayList<>();
        for (Path file : files) {
            findings.addAll(findings(file));
        }
        assertThat(findings)
                .as(
                        "%d lines in %s cite the scripts the host tool replaced",
                        findings.size(), TESTS)
                .isEmpty();
    }

    @Test
    void findsNothingToCheckUnderAnEmptyTree(@TempDir Path root) throws IOException {
        // What the first check guards against: an empty walk, which reports no finding.
        assertThat(sources(root)).isEmpty();
    }

    @Test
    void namesEachCitationByFileAndLineAndLeavesExpectedOutputAlone(@TempDir Path root)
            throws IOException {
        final String script = "porelo-" + "host-" + "person";
        final Path file = root.resolve("A.java");
        Files.writeString(
                file,
                String.join(
                        "\n",
                        "class A {",
                        "    /" + "/ p" + "y: test_something",
                        "    void a() {",
                        "        assertThat(err).isEqualTo(\""
                                + script
                                + ": runs only as root\\n\");",
                        "        assertThat(code)",
                        "                .as(",
                        "                        \"the exit code " + script + " chose\")",
                        "                .isEqualTo(69);",
                        "        // as " + script + " did",
                        "        /* as in the Pyt" + "hon */",
                        "        assertThat(x).as(\"what the prede" + "cessor refused\");",
                        "        String json = ProjectRecords.pyt" + "honJson(m, true);",
                        "        Path tool = TestPrograms.find(\"pyt" + "hon3\");",
                        "    }",
                        "    void keepsPyt" + "honOrder() {}",
                        "}"));

        assertThat(findings(file))
                .containsExactly(
                        file + ":2: " + "/" + "/ p" + "y: test_something",
                        file + ":7: \"the exit code " + script + " chose\"",
                        file + ":9: // as " + script + " did",
                        file + ":10: /* as in the Pyt" + "hon */",
                        file + ":11: \"what the prede" + "cessor refused\"",
                        file + ":15: keepsPyt" + "honOrder");
    }

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<String> findings(Path file) {
        final String source;
        try {
            source = Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final List<Token> tokens = tokens(source);
        final List<String> findings = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            final Token token = tokens.get(i);
            if (depth == 0 && isMessageCall(tokens, i)) {
                depth = 1;
                i++;
                continue;
            }
            if (depth > 0 && token.kind() == Kind.PUNCT) {
                depth += token.text().equals("(") ? 1 : token.text().equals(")") ? -1 : 0;
            }
            final boolean scriptNamesCount =
                    token.kind() == Kind.COMMENT || (depth > 0 && token.kind() == Kind.STRING);
            if ((token.kind() == Kind.COMMENT && CITATION.matcher(token.text()).find())
                    || LANGUAGE.matcher(token.text()).find()
                    || SUCCESSION.matcher(token.text()).find()
                    || (scriptNamesCount && SCRIPT.matcher(token.text()).find())) {
                findings.add(file + ":" + token.line() + ": " + token.text().strip());
            }
        }
        return findings;
    }

    private static boolean isMessageCall(List<Token> tokens, int i) {
        return i > 0
                && i + 1 < tokens.size()
                && tokens.get(i).kind() == Kind.NAME
                && MESSAGE_CALLS.contains(tokens.get(i).text())
                && tokens.get(i - 1).text().equals(".")
                && tokens.get(i + 1).text().equals("(");
    }

    private enum Kind {
        COMMENT,
        STRING,
        NAME,
        PUNCT
    }

    /** One line's part of a Java token, so a finding names the line it stands on. */
    private record Token(Kind kind, String text, int line) {}

    private static List<Token> tokens(String source) {
        final List<Token> tokens = new ArrayList<>();
        int line = 1;
        int i = 0;
        while (i < source.length()) {
            final char c = source.charAt(i);
            final int end;
            final Kind kind;
            if (source.startsWith("//", i)) {
                end = endOf(source, i, "\n", false);
                kind = Kind.COMMENT;
            } else if (source.startsWith("/*", i)) {
                end = endOf(source, i + 2, "*/", false) + 2;
                kind = Kind.COMMENT;
            } else if (source.startsWith("\"\"\"", i)) {
                end = endOf(source, i + 3, "\"\"\"", true) + 3;
                kind = Kind.STRING;
            } else if (c == '"' || c == '\'') {
                end = endOf(source, i + 1, String.valueOf(c), true) + 1;
                kind = Kind.STRING;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < source.length() && Character.isJavaIdentifierPart(source.charAt(j))) {
                    j++;
                }
                end = j;
                kind = Kind.NAME;
            } else {
                if (c == '\n') {
                    line++;
                }
                if (!Character.isWhitespace(c)) {
                    tokens.add(new Token(Kind.PUNCT, String.valueOf(c), line));
                }
                i++;
                continue;
            }
            final int stop = Math.min(end, source.length());
            for (String part : source.substring(i, stop).split("\n", -1)) {
                tokens.add(new Token(kind, part, line));
                line++;
            }
            line--;
            i = stop;
        }
        return tokens;
    }

    private static int endOf(String source, int from, String close, boolean escapes) {
        int i = from;
        while (i < source.length() && !source.startsWith(close, i)) {
            i += escapes && source.charAt(i) == '\\' ? 2 : 1;
        }
        return i;
    }
}
