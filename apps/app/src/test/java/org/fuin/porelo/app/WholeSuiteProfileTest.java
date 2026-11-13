package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Tests that the tests a run of the touched modules leaves out still run in the whole suite.
 *
 * <p>The root pom's surefire configuration excludes the tests tagged {@code slow}, and the profile
 * {@code whole} runs them again; CI's build names that profile. Should the profile stop clearing
 * the exclusion, or CI stop naming it, those tests would run nowhere and every build would stay
 * green.
 */
@org.junit.jupiter.api.Tag("documents")
class WholeSuiteProfileTest {

    private static final Path ROOT = Path.of("..", "..");

    private static final String SLOW = "slow";

    /**
     * The tag of the tests that take a second account on the machine, which neither a hand-over's
     * machine nor CI has: left out by the profile too.
     */
    private static final String SECOND_ACCOUNT = "second-account";

    private static final String PROFILE = "whole";

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * The tag on the class itself, not on one method, nor in a comment: only annotations between.
     */
    private static final Pattern SLOW_TAG = classTag(SLOW);

    private static final Pattern SECOND_ACCOUNT_TAG = classTag(SECOND_ACCOUNT);

    private static Pattern classTag(final String tag) {
        return Pattern.compile(
                "(?m)^@Tag\\(\""
                        + tag
                        + "\"\\)\\s*\\n(?:@[^\\n]*\\n)*(?:public\\s+|final\\s+)*class\\s");
    }

    private static Element pom() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder()
                .parse(ROOT.resolve("pom.xml").toFile())
                .getDocumentElement();
    }

    private static List<Element> children(final Element parent, final String name) {
        final List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                found.add(element);
            }
        }
        return found;
    }

    private static Optional<Element> child(final Element parent, final String... path) {
        Optional<Element> at = Optional.of(parent);
        for (final String name : path) {
            at = at.flatMap(element -> children(element, name).stream().findFirst());
        }
        return at;
    }

    private static Map<String, String> properties(final Element owner) {
        final Map<String, String> found = new HashMap<>();
        child(owner, "properties")
                .ifPresent(
                        properties -> {
                            for (Node node = properties.getFirstChild();
                                    node != null;
                                    node = node.getNextSibling()) {
                                if (node instanceof Element element) {
                                    found.put(
                                            element.getTagName(), element.getTextContent().strip());
                                }
                            }
                        });
        return found;
    }

    /** The surefire configuration every module inherits, from the root's plugin management. */
    private static Element surefire(final Element pom) {
        return child(pom, "build", "pluginManagement", "plugins").stream()
                .flatMap(plugins -> children(plugins, "plugin").stream())
                .filter(
                        plugin ->
                                child(plugin, "artifactId")
                                        .map(id -> id.getTextContent().strip())
                                        .filter("maven-surefire-plugin"::equals)
                                        .isPresent())
                .findFirst()
                .flatMap(plugin -> child(plugin, "configuration"))
                .orElseThrow(
                        () -> new AssertionError("the root pom manages no surefire configuration"));
    }

    private static Optional<Element> profile(final Element pom, final String id) {
        return child(pom, "profiles").stream()
                .flatMap(profiles -> children(profiles, "profile").stream())
                .filter(
                        profile ->
                                child(profile, "id")
                                        .map(element -> element.getTextContent().strip())
                                        .filter(id::equals)
                                        .isPresent())
                .findFirst();
    }

    /**
     * The tags surefire leaves out with these properties, its expression resolved as Maven does.
     */
    private static Set<String> excluded(
            final Element surefire, final Map<String, String> properties) {
        final String expression =
                child(surefire, "excludedGroups").map(e -> e.getTextContent().strip()).orElse("");
        final Matcher matcher = PROPERTY.matcher(expression);
        final String resolved =
                matcher.replaceAll(
                        match ->
                                Matcher.quoteReplacement(
                                        properties.getOrDefault(match.group(1), match.group())));
        return Arrays.stream(resolved.split("[,|&!()\\s]+"))
                .filter(tag -> !tag.isEmpty())
                .collect(Collectors.toSet());
    }

    private static List<Path> testSources() throws IOException {
        final List<Path> sources = new ArrayList<>();
        Files.walkFileTree(
                ROOT,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            final Path directory, final BasicFileAttributes attributes) {
                        final String name = String.valueOf(directory.getFileName());
                        return !directory.equals(ROOT)
                                        && ("target".equals(name) || name.startsWith("."))
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            final Path file, final BasicFileAttributes attributes) {
                        if (file.toString().contains("/src/test/java/")
                                && file.getFileName().toString().endsWith(".java")) {
                            sources.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return sources;
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void theWholeProfileRunsTheTestsARunOfTheTouchedModulesLeavesOut() throws Exception {
        final Element pom = pom();
        final Element surefire = surefire(pom);
        final Map<String, String> plain = properties(pom);

        // A guard on an empty set passes forever: first that the tag is there and left out at all.
        assertThat(
                        testSources().stream()
                                .filter(path -> SLOW_TAG.matcher(read(path)).find())
                                .map(path -> path.getFileName().toString()))
                .as("test classes tagged %s", SLOW)
                // Elsewhere a slow test would be skipped by every run without the profile, the FFM
                // check's among them.
                .containsExactlyInAnyOrder(
                        "GitTreeWritesContractTest.java",
                        "GitTreeWritesTest.java",
                        "GitTreeStoreTest.java");
        assertThat(excluded(surefire, plain))
                .as("the tags a build without -P%s leaves out", PROFILE)
                .containsExactlyInAnyOrder(SLOW, SECOND_ACCOUNT);

        final Element whole =
                profile(pom, PROFILE)
                        .orElseThrow(
                                () -> new AssertionError("the root pom has no profile " + PROFILE));
        final Map<String, String> withWhole = new HashMap<>(plain);
        withWhole.putAll(properties(whole));
        assertThat(excluded(surefire, withWhole))
                .as("the tags a build with -P%s leaves out", PROFILE)
                .containsExactly(SECOND_ACCOUNT);
        assertThat(
                        testSources().stream()
                                .filter(path -> SECOND_ACCOUNT_TAG.matcher(read(path)).find())
                                .map(path -> path.getFileName().toString()))
                .as("test classes tagged %s, which no run takes unless asked for", SECOND_ACCOUNT)
                .containsExactly("SocketPeerOtherAccountTest.java");
        assertThat(child(whole, "build"))
                .as("a surefire configuration of the profile's own, which this test does not read")
                .isEmpty();
    }

    @Test
    void noModuleChoosesTestsByTagOfItsOwn() throws IOException {
        // A module's own groups or excludedGroups would take the place of the root's, and the
        // profile would no longer reach it.
        final List<String> choosing;
        try (Stream<Path> paths = Files.walk(ROOT)) {
            choosing =
                    paths.filter(path -> path.getFileName().toString().equals("pom.xml"))
                            .filter(
                                    path ->
                                            !path.normalize()
                                                    .equals(ROOT.resolve("pom.xml").normalize()))
                            .filter(path -> !path.toString().contains("/target/"))
                            .filter(path -> !path.toString().contains("/.claude/"))
                            .filter(
                                    path -> {
                                        final String text = read(path);
                                        return text.contains("<groups>")
                                                || text.contains("<excludedGroups>");
                                    })
                            .map(path -> ROOT.relativize(path).toString())
                            .toList();
        }

        assertThat(choosing).as("modules that choose tests by tag themselves").isEmpty();
    }

    @Test
    void ciBuildsTheWholeSuiteWithTheProfile() throws IOException {
        final List<String> runs;
        try (Stream<String> lines = Files.lines(ROOT.resolve(".github/workflows/build.yml"))) {
            runs =
                    lines.map(String::strip)
                            .filter(line -> line.startsWith("run: ./mvnw"))
                            .filter(line -> !line.contains("-DskipTests"))
                            .filter(line -> !line.contains(" -pl ") && !line.contains(" -N "))
                            .filter(line -> line.matches(".*\\s(test|verify|install)(\\s.*)?"))
                            .toList();
        }

        assertThat(runs).as("CI's runs of the whole reactor's tests").isNotEmpty();
        assertThat(runs)
                .as("CI's runs of the whole reactor's tests that do not name -P%s", PROFILE)
                .allMatch(line -> line.matches(".*\\s-P(\\S*,)?" + PROFILE + "(,\\S*)?(\\s.*)?"));
    }
}
