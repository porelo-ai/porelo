package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps what a task container's name looks like in {@code ContainerName} alone.
 *
 * <p>A task's name begins {@code porelo-} or {@code porelo_}. A check of its own beginning
 * elsewhere took the one form for every task, and a task of the other was nobody's: not listed, not
 * stopped, not cleaned up, given no socket. So a name pattern or a prefix check outside {@code
 * ContainerName} fails here, and a name that is no container's - a forge identity, a prompt, a
 * package file - is listed below by file with the reason, so a new one is a decision.
 */
class ContainerNameShapeTest {

    /** The one place that knows the shape. */
    private static final String CONTAINER_NAME =
            "runtime/src/main/java/org/fuin/porelo/runtime/ContainerName.java";

    /** Names beginning like a container's that are none, by file, with why. */
    private static final Map<String, String> NO_CONTAINER =
            Map.ofEntries(
                    Map.entry(
                            "acceptance/kit/src/main/java/org/fuin/porelo/acceptance/TerminalSteps.java",
                            "the prompt 'porelo[<task>]' a task's shell shows"),
                    Map.entry(
                            "runtime/src/main/java/org/fuin/porelo/runtime/Podman.java",
                            "the prompt 'porelo[<task>]' it sets in a task's shell"),
                    Map.entry(
                            "dist/package-check/src/main/java/org/fuin/porelo/packagecheck/Main.java",
                            "the package file 'porelo-<version>.rpm'"),
                    Map.entry(
                            "apps/app-vault/src/main/java/org/fuin/porelo/app/IdentityCreateCommand.java",
                            "a person's login, which may not be a forge identity 'porelo-<login>'"),
                    Map.entry(
                            "vault/src/main/java/org/fuin/porelo/vault/PersonCard.java",
                            "a person's login, which may not be a forge identity 'porelo-<login>'"),
                    Map.entry(
                            "organization/git-tree/src/main/java/org/fuin/porelo/organization/gittree/TreeFiles.java",
                            "a person's login, which may not be a forge identity 'porelo-<login>'"),
                    Map.entry(
                            "host/src/main/java/org/fuin/porelo/host/PushJudgement.java",
                            "a pusher, whose forge identity is 'porelo-<login>'"),
                    Map.entry(
                            "host/src/main/java/org/fuin/porelo/host/FollowPeople.java",
                            "the line 'porelo-people:' a host answers with"),
                    Map.entry(
                            "host/src/main/java/org/fuin/porelo/host/FollowProjects.java",
                            "the line 'porelo-host: ' a host answers with"),
                    Map.entry(
                            "host/src/main/java/org/fuin/porelo/host/PoreloHost.java",
                            "the line 'porelo-host:' a host answers with"),
                    Map.entry(
                            "core/src/main/java/org/fuin/porelo/core/project/ShortName.java",
                            "a short name, which core refuses to begin 'porelo-' below"
                                    + " ContainerName's module"),
                    Map.entry(
                            "apps/app-model/src/main/java/org/fuin/porelo/app/TaskNames.java",
                            "a repository's name, whose 'porelo-' a task named after it drops"),
                    Map.entry(
                            "apps/app-task/src/main/java/org/fuin/porelo/app/CommandMask.java",
                            "a token Porelo issues, 'porelo_<kind>_<value>', masked in a command"));

    /** A string literal, its escapes kept. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** In a literal: a character class right after the beginning, or the beginning anchored. */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("porelo[-_]?\\[|\\^\\(?porelo[-_\\[]");

    /** In code: a check of a name's beginning, or a cut of it. */
    private static final Pattern PREFIX_CHECK =
            Pattern.compile(
                    "startsWith\\(\\s*(?:\"porelo[-_]|(?:[\\w.]*\\.)?ContainerName\\.\\w*PREFIX)"
                            + "|\"porelo[-_]\"\\s*\\.length\\(\\)");

    @Test
    void noNameIsTakenForATaskContainersByItsShapeOutsideContainerName() throws IOException {
        final Path root = Path.of("").toAbsolutePath().getParent().getParent();
        final Map<String, List<String>> found = new TreeMap<>();
        final List<Path> sources =
                RepositoryFiles.of(root).stream().filter(path -> isMainSource(root, path)).toList();
        assertThat(sources)
                .as("the main sources scanned under %s", root)
                .contains(root.resolve(CONTAINER_NAME))
                .contains(
                        root.resolve(
                                "acceptance/kit/src/main/java/org/fuin/porelo/acceptance/LoopWatch.java"));
        for (final Path source : sources) {
            final List<String> lines = shapes(source);
            if (!lines.isEmpty()) {
                found.put(root.relativize(source).toString(), lines);
            }
        }

        assertThat(found)
                .as("the shape is ContainerName's, so the scan finds it there")
                .containsKey(CONTAINER_NAME);
        assertThat(NO_CONTAINER.keySet())
                .as("each name listed as no container's is still there to be found")
                .allSatisfy(file -> assertThat(found).as(file).containsKey(file));
        final Map<String, List<String>> outside = new TreeMap<>(found);
        outside.remove(CONTAINER_NAME);
        outside.keySet().removeAll(NO_CONTAINER.keySet());
        assertThat(outside)
                .as(
                        "a container name's pattern or beginning checked outside ContainerName:"
                                + " use ContainerName.isPorelo, isTask or NAME_PATTERN, or list a"
                                + " name that is no container's with its reason")
                .isEmpty();
    }

    @Test
    void theScanFindsEveryShapeItLooksFor() {
        // A scan that finds nothing passes whatever the sources say.
        for (final String line :
                List.of(
                        "if (name.matches(\"porelo-[a-z0-9_-]+\")) {",
                        "Pattern.compile(\"porelo[-_][a-z]+\");",
                        "\"podman ps --filter name=^porelo-\"",
                        "if (name.startsWith(\"porelo-\")) {",
                        "if (name.startsWith(\"porelo_\")) {",
                        "request.task().startsWith(ContainerName.PREFIX)",
                        "x.startsWith(org.fuin.porelo.runtime.ContainerName.PREFIX)",
                        "name.substring(\"porelo-\".length())")) {
            assertThat(shapes(List.of(line))).as(line).hasSize(1);
        }
        for (final String line :
                List.of(
                        "return ContainerName.isTask(name);",
                        "\"porelo-\" + login",
                        "// name.startsWith(\"porelo-\")",
                        " * matches(\"porelo-[a-z]+\")")) {
            assertThat(shapes(List.of(line))).as(line).isEmpty();
        }
    }

    private static boolean isMainSource(Path root, Path path) {
        final String name = "/" + root.relativize(path);
        return name.endsWith(".java")
                && !name.contains("/target/")
                && !name.contains("/.")
                && name.contains("/src/main/java/");
    }

    private static List<String> shapes(Path source) {
        try {
            return shapes(Files.readAllLines(source, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + source, ex);
        }
    }

    private static List<String> shapes(List<String> lines) {
        final List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).strip();
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                continue;
            }
            boolean shaped = PREFIX_CHECK.matcher(line).find();
            final Matcher literal = LITERAL.matcher(line);
            while (!shaped && literal.find()) {
                shaped = NAME_PATTERN.matcher(literal.group(1)).find();
            }
            if (shaped) {
                found.add((i + 1) + ": " + line);
            }
        }
        return found;
    }
}
