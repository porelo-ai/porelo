package org.fuin.porelo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Publish sends jars after the Build alone on a forge that publishes no packages, while Central
 * still requires the legs or their proof and newly built binaries.
 *
 * <p>The small evaluator reads the actual YAML conditions and supports only their boolean,
 * comparison and function forms. It does not model a forge's scheduler or implicit status checks;
 * those still need a run on each forge. The deploy script runs against a stand-in for Maven.
 */
class PublishWorkflowTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"refs/heads/main", "refs/tags/v1.0.0"})
    void aForgeWithoutPackagesDeploysAfterTheBuildAlone(final String ref) throws IOException {
        final Map<String, String> inputs = inputs("false", ref);
        assertThat(runs(deployJob(), inputs)).isTrue();
        final List<String> expected =
                new ArrayList<>(List.of("checkout", "Which channel", "The pinned GraalVM"));
        if (ref.startsWith("refs/tags/")) {
            expected.add("No snapshot in a release");
        }
        expected.add("Deploy the jars");
        assertThat(runningSteps(inputs)).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "false", "other"})
    void failedOrCanceledBuildsAndUnpublishableEventsNeverDeploy(final String publish)
            throws IOException {
        for (final String result : List.of("failure", "cancelled", "skipped")) {
            final Map<String, String> inputs = qualifiedInputs(publish);
            inputs.put("needs.build.result", result);
            assertThat(runs(deployJob(), inputs)).as("Build: " + result).isFalse();
        }
        final Map<String, String> canceled = qualifiedInputs(publish);
        canceled.put("cancelled", "true");
        assertThat(runs(deployJob(), canceled)).as("canceled workflow").isFalse();
        for (final String ref : List.of("refs/heads/porelo/x", "refs/tags/other")) {
            final Map<String, String> inputs = qualifiedInputs(publish);
            inputs.put("github.ref", ref);
            assertThat(runs(deployJob(), inputs)).as(ref).isFalse();
        }
        for (final String event : List.of("pull_request", "workflow_dispatch")) {
            final Map<String, String> inputs = qualifiedInputs(publish);
            inputs.put("github.event_name", event);
            assertThat(runs(deployJob(), inputs)).as(event).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "other"})
    void centralMainAndTagsStillNeedLegsOrProofWithNative(final String publish) throws IOException {
        for (final String ref : List.of("refs/heads/main", "refs/tags/v1.0.0")) {
            for (final String legs : List.of("success", "skipped", "failure", "cancelled")) {
                for (final String proof : List.of("yes", "no", "")) {
                    for (final String nativeResult :
                            List.of("success", "skipped", "failure", "cancelled")) {
                        final Map<String, String> inputs = inputs(publish, ref);
                        inputs.put("needs.legs.result", legs);
                        inputs.put("needs.proven.outputs.proven", proof);
                        inputs.put("needs.native.result", nativeResult);
                        assertThat(runs(deployJob(), inputs))
                                .as(
                                        "%s: legs %s, proof '%s', native %s",
                                        ref, legs, proof, nativeResult)
                                .isEqualTo(
                                        legs.equals("success")
                                                || (proof.equals("yes")
                                                        && nativeResult.equals("success")));
                    }
                }
            }
        }
        assertThat((List<?>) deployJob().get("needs"))
                .isEqualTo(List.of("build", "proven", "legs", "native"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "other"})
    void centralKeepsEveryPackageStep(final String publish) throws IOException {
        final Map<String, String> inputs = qualifiedInputs(publish);
        assertThat(runningSteps(inputs))
                .contains(
                        "Import the signing key",
                        "Build and install",
                        "download",
                        "Put the binaries where the packaging expects them",
                        "Build the packages",
                        "Check the packages",
                        "Deploy the jars",
                        "Publish the packages",
                        "The packages are indexed, not merely stored");
        inputs.put("steps.channel.outputs.channel", "releases");
        assertThat(runningSteps(inputs)).contains("No snapshot in a release");
    }

    @ParameterizedTest
    @CsvSource({
        "false, '', '', false",
        "false, forge-release, forge-release, true",
        "'', '', central-sonatype-release, true",
        "true, '', central-sonatype-release, true",
        "other, '', central-sonatype-release, true",
        "'', forge-release, forge-release, true",
        "false, 'profile with spaces', 'profile with spaces', true",
        "false, '$(touch injected)', '$(touch injected)', true"
    })
    void theProfileIsGuardedBeforeMavenAndPassedAsOneArgument(
            final String publish,
            final String profile,
            final String expected,
            final boolean allowed)
            throws IOException, InterruptedException {
        final String script = String.valueOf(deployTheJars().get("run"));
        assertThat(script).doesNotContain("${{");
        assertThat(script.indexOf("[ -n \"$PORELO_DEPLOY_PROFILE\" ]"))
                .as("the profile guard precedes any Maven invocation")
                .isBetween(0, script.indexOf("./mvnw") - 1);
        final Path arguments = directory.resolve("arguments");
        final Path maven = directory.resolve("mvnw");
        Files.writeString(maven, "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$DEPLOY_ARGUMENTS\"\n");
        Files.setPosixFilePermissions(maven, PosixFilePermissions.fromString("rwx------"));
        final Path output = directory.resolve("output");
        final ProcessBuilder command =
                new ProcessBuilder(
                                "bash",
                                "--noprofile",
                                "--norc",
                                "-e",
                                "-o",
                                "pipefail",
                                "-c",
                                script)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        command.environment().clear();
        command.environment().put("PATH", "/usr/bin:/bin");
        command.environment().put("PORELO_PUBLISH", publish);
        command.environment().put("PORELO_DEPLOY_PROFILE", profile);
        command.environment().put("DEPLOY_ARGUMENTS", arguments.toString());
        final Process process = command.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("the step ends").isTrue();
            assertThat(process.exitValue() == 0).as(Files.readString(output)).isEqualTo(allowed);
            assertThat(Files.exists(arguments)).as("whether Maven was called").isEqualTo(allowed);
            assertThat(Files.exists(directory.resolve("injected"))).isFalse();
            if (allowed) {
                assertThat(Files.readAllLines(arguments))
                        .containsExactly(
                                "-B",
                                "deploy",
                                "-P" + expected,
                                "-s",
                                "settings.xml",
                                "-DskipTests");
            } else {
                assertThat(Files.readString(output)).contains("PORELO_DEPLOY_PROFILE");
            }
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void deployReadsItsProfileAndRegistryCredentialsFromTheEnvironment() throws Exception {
        final Map<?, ?> env = (Map<?, ?>) deployTheJars().get("env");
        assertThat(env.get("PORELO_DEPLOY_PROFILE")).isEqualTo("${{ vars.PORELO_DEPLOY_PROFILE }}");
        assertThat(env.get("PORELO_PUBLISH")).isEqualTo("${{ vars.PORELO_PUBLISH }}");
        assertThat(env.get("MAVEN_SNAPSHOTS_URL")).isEqualTo("${{ vars.MAVEN_SNAPSHOTS_URL }}");
        assertThat(env.get("PORELO_PUBLISHER_TOKEN"))
                .isEqualTo("${{ secrets.PORELO_PUBLISHER_TOKEN }}");
        final var settings =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(ROOT.resolve("settings.xml").toFile());
        final var xpath = XPathFactory.newInstance().newXPath();
        final String server = "/settings/servers/server[id='porelo-forge']/";
        assertThat(xpath.evaluate(server + "username", settings)).isEqualTo("porelo-publisher");
        assertThat(xpath.evaluate(server + "password", settings))
                .isEqualTo("${env.PORELO_PUBLISHER_TOKEN}");
    }

    @Test
    void theDryRunStillChecksCentral() throws IOException {
        final Map<?, ?> build = (Map<?, ?>) jobs().get("build");
        final Map<?, ?> dryRun =
                steps(build).stream()
                        .filter(step -> "Deploy dry run".equals(step.get("name")))
                        .findFirst()
                        .orElseThrow();
        assertThat(String.valueOf(dryRun.get("run"))).contains("-Pcentral-sonatype-release");
    }

    private static Map<String, String> inputs(final String publish, final String ref) {
        return new HashMap<>(
                Map.of(
                        "cancelled",
                        "false",
                        "needs.build.result",
                        "success",
                        "needs.legs.result",
                        "skipped",
                        "needs.proven.outputs.proven",
                        "",
                        "needs.native.result",
                        "skipped",
                        "vars.PORELO_PUBLISH",
                        publish,
                        "github.event_name",
                        "push",
                        "github.ref",
                        ref,
                        "steps.channel.outputs.channel",
                        ref.startsWith("refs/tags/") ? "releases" : "snapshots",
                        "steps.packages-cache.outputs.cache-hit",
                        ""));
    }

    private static Map<String, String> qualifiedInputs(final String publish) {
        final Map<String, String> inputs = inputs(publish, "refs/heads/main");
        inputs.put("needs.legs.result", "success");
        inputs.put("needs.proven.outputs.proven", "yes");
        inputs.put("needs.native.result", "success");
        return inputs;
    }

    private static List<String> runningSteps(final Map<String, String> inputs) throws IOException {
        final List<String> running = new ArrayList<>();
        for (final Map<?, ?> step : steps(deployJob())) {
            if (runs(step, inputs)) {
                final String uses = String.valueOf(step.get("uses"));
                running.add(
                        step.get("name") != null
                                ? String.valueOf(step.get("name"))
                                : uses.startsWith("actions/checkout@")
                                        ? "checkout"
                                        : uses.startsWith("actions/download-artifact@")
                                                ? "download"
                                                : uses);
            }
        }
        return running;
    }

    private static boolean runs(final Map<?, ?> block, final Map<String, String> inputs) {
        return block.get("if") == null
                || new Condition(String.valueOf(block.get("if")), inputs).evaluate();
    }

    private static Map<?, ?> deployTheJars() throws IOException {
        return steps(deployJob()).stream()
                .filter(step -> "Deploy the jars".equals(step.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static List<Map<?, ?>> steps(final Map<?, ?> job) {
        final List<Map<?, ?>> steps = new ArrayList<>();
        for (final Object step : (List<?>) job.get("steps")) {
            steps.add((Map<?, ?>) step);
        }
        return steps;
    }

    private static Map<?, ?> deployJob() throws IOException {
        return (Map<?, ?>) jobs().get("deploy");
    }

    private static Map<?, ?> jobs() throws IOException {
        final Map<?, ?> document =
                new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(Files.readString(ROOT.resolve(".github/workflows/build.yml")));
        return (Map<?, ?>) document.get("jobs");
    }

    /**
     * Parses only the expression forms used by the deploy job and its steps; unknown forms fail.
     */
    private static final class Condition {
        private static final Pattern TOKEN =
                Pattern.compile("\\s*('[^']*'|&&|\\|\\||==|!=|[!(),]|[A-Za-z_][A-Za-z_0-9.-]*)");
        private final List<String> tokens = new ArrayList<>();
        private final Map<String, String> inputs;
        private int position;

        Condition(final String expression, final Map<String, String> inputs) {
            this.inputs = inputs;
            final Matcher matcher = TOKEN.matcher(expression.strip());
            int end = 0;
            while (matcher.find()) {
                assertThat(matcher.start()).as("no unsupported expression text").isEqualTo(end);
                tokens.add(matcher.group(1));
                end = matcher.end();
            }
            assertThat(end).isEqualTo(expression.strip().length());
        }

        boolean evaluate() {
            final boolean result = or();
            assertThat(position).as("the whole condition was read").isEqualTo(tokens.size());
            return result;
        }

        private boolean or() {
            boolean result = and();
            while (take("||")) {
                result = and() | result;
            }
            return result;
        }

        private boolean and() {
            boolean result = term();
            while (take("&&")) {
                result = term() & result;
            }
            return result;
        }

        private boolean term() {
            if (take("!")) {
                return !term();
            }
            if (take("(")) {
                final boolean result = or();
                require(")");
                return result;
            }
            if (take("cancelled")) {
                require("(");
                require(")");
                return Boolean.parseBoolean(inputs.get("cancelled"));
            }
            if (take("startsWith")) {
                require("(");
                final String value = value();
                require(",");
                final String prefix = value();
                require(")");
                return value.startsWith(prefix);
            }
            final String left = value();
            if (take("==")) {
                return left.equals(value());
            }
            require("!=");
            return !left.equals(value());
        }

        private String value() {
            final String token = tokens.get(position++);
            if (token.startsWith("'")) {
                return token.substring(1, token.length() - 1);
            }
            assertThat(inputs).as("known input " + token).containsKey(token);
            return inputs.get(token);
        }

        private boolean take(final String token) {
            if (position < tokens.size() && tokens.get(position).equals(token)) {
                position++;
                return true;
            }
            return false;
        }

        private void require(final String token) {
            assertThat(take(token)).as("expected " + token + " at token " + position).isTrue();
        }
    }
}
