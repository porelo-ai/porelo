package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What an image records about its build: a person reads it to learn what a task can run. */
class ImageSettingsTest {

    private static final String W = ImageSettings.WITHHELD;

    private static Project project(final String snippet) {
        return new Project("uc", "", SecurityClass.OFFLINE, "ubuntu:24.04", snippet);
    }

    private static ImageSettings of(final String snippet) {
        return of(snippet, "1.2.3");
    }

    private static ImageSettings of(final String snippet, final String version) {
        final Project project = project(snippet);
        return ImageSettings.of(
                project,
                "base source",
                Collections.nCopies(project.imageSnippetLines().size(), "lines source"),
                "class source",
                "an-agent",
                version);
    }

    private static List<String> packages(final String snippet) {
        return of(snippet).packages().stream().map(ImageSettings.Sourced::value).toList();
    }

    private static List<String> lines(final String snippet) {
        return of(snippet).lines().stream().map(ImageSettings.Sourced::value).toList();
    }

    @Test
    void whatARepositorysFileTookOutReadsBackAndAnImageWithoutStaysAsItWas() {
        final ImageSettings plain = of("RUN true");
        final ImageSettings taken =
                plain.withRemoved(
                        List.of(new ImageSettings.Sourced("image.env.DROP", "the local override")));

        assertThat(ImageSettings.parse(taken.json())).contains(taken);
        assertThat(plain.json())
                .as("a label without removals is written as before, so its image keeps its id")
                .doesNotContain("removed");
        assertThat(ImageSettings.parse(plain.json()).orElseThrow().removed()).isEmpty();
    }

    @Test
    void whatIsRecordedReadsBackWhole() {
        final ImageSettings written =
                of("RUN apt-get install -y \"maven\"\nRUN echo \"$B \\\"c\\\"\"\nRUN echo ü");

        assertThat(ImageSettings.parse(written.json()))
                .as("the answer is what was recorded, quotes and all")
                .contains(written);
        assertThat(written.json()).as("a label's value is one line").doesNotContain("\n");
        assertThat(written.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(written.securityClass()).isEqualTo("offline");
        assertThat(written.tools())
                .as("what the image's own package step asks for")
                .containsExactly(
                        "curl", "ca-certificates", "git", "openssh-client", "tmux", "python3");
    }

    @Test
    void eachLineAndEachPackageKeepItsOwnSource() {
        final ImageSettings written =
                ImageSettings.of(
                        project("RUN apt-get install -y maven\nRUN dnf install -y gcc"),
                        "base",
                        List.of("first", "second"),
                        "class",
                        "",
                        "");

        assertThat(written.lines())
                .extracting(ImageSettings.Sourced::source)
                .containsExactly("first", "second");
        assertThat(written.packages())
                .containsExactly(
                        new ImageSettings.Sourced("maven", "first"),
                        new ImageSettings.Sourced("gcc", "second"));
    }

    @Test
    void thePackagesAreWhatTheInstallCommandsName() {
        // What a person asks: is Maven in it, is Java in it.
        assertThat(
                        packages(
                                """
                                # apt-get install commented-out
                                RUN apt-get update && apt-get install -y --no-install-recommends \\
                                    maven openjdk-21-jdk=21.0.1 && rm -rf /var/lib/apt/lists/*
                                RUN dnf -y install gcc; apk add --no-cache 'jq'
                                RUN microdnf install $FROM_A_VARIABLE make | tee /dev/null
                                RUN curl -fsSL https://example.org/gradle.zip -o /tmp/gradle.zip
                                """))
                .as("each install command's own words, and nothing of the commands around them")
                .containsExactly("maven", "openjdk-21-jdk=21.0.1", "gcc", "jq", "make");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "RUN echo apt-get is not used here",
                "RUN myapt install thing",
                "RUN echo apt-get install maven",
                "RUN echo \"x; apt-get install maven\"",
                "RUN true && echo apt-get install maven",
                "ENV NOTE=apt-get install maven",
                "COPY apt-get install maven",
                "# RUN apt-get install maven",
            })
    void anInstallerCountsOnlyAsACommandsOwnWord(final String snippet) {
        // Reported as present, it is a tool a person trusts and that is not in the image.
        assertThat(packages(snippet)).as(snippet).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "RUN apt-get install -y -t bookworm maven",
                "RUN apt-get install -y -o APT::Install-Recommends=false maven",
                "RUN apt-get -o Dpkg::Use-Pty=0 install maven",
                "RUN apt-get install --some-new-option perhaps-its-value maven",
                "RUN sudo apt-get install -y maven",
                "RUN sudo -E apt-get install -y maven",
                "RUN DEBIAN_FRONTEND=noninteractive apt-get install -y maven",
                "RUN true && DEBIAN_FRONTEND=noninteractive sudo apt-get install maven",
                "RUN true || apt-get install maven",
                "run apt-get install maven 2>&1",
                "RUN apt-get install maven > /dev/null",
                "RUN apt-get install maven >/dev/null 2>/dev/null",
                "RUN apk add -t build-deps maven",
                "RUN dnf install --enablerepo=extras -y maven",
            })
    void optionsAndTheirValuesAreNoPackage(final String snippet) {
        assertThat(packages(snippet)).as(snippet).containsExactly("maven");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "ENV NPM_TOKEN=\"s3cret\" | ENV NPM_TOKEN=%1$s",
                "env NPM_TOKEN=s3cret | ENV NPM_TOKEN=%1$s",
                "ENV A=s3cret B=\"s3cret and more\" | ENV A=%1$s B=%1$s",
                "ENV NPM_TOKEN s3cret and more | ENV NPM_TOKEN %1$s",
                "ENV 'A=s3cret | ENV %1$s",
                "ENV | ENV",
            })
    void anEnvLineIsRecordedByItsNamesOnly(final String line, final String expected) {
        final String snippet = line;

        assertThat(lines(snippet)).containsExactly(expected.formatted(W));
        assertThat(of(snippet).json()).doesNotContain("s3cret");
    }

    @Test
    void anEnvLineContinuedOnTheNextLinesIsOneRecordOfItsNames() {
        final String snippet = "ENV A=s3cret \\\n    B=\"s3cret\" \\\n    C=s3cret\nRUN true";

        assertThat(lines(snippet))
                .containsExactly("ENV A=" + W + " B=" + W + " C=" + W, "RUN true");
        assertThat(of(snippet).json()).doesNotContain("s3cret");
    }

    @Test
    void aCommentInsideAContinuedEnvLineDoesNotEndIt() {
        final String snippet = "ENV A=public \\\n# a comment\n    TOKEN=s3cret\nRUN true";

        assertThat(lines(snippet)).containsExactly("ENV A=" + W + " TOKEN=" + W, "RUN true");
        assertThat(of(snippet).json()).doesNotContain("s3cret");
    }

    @Test
    void aUrlsCredentialContinuedOnTheNextLineIsCovered() {
        final String snippet = "RUN curl https://user:\\\ns3cret@example.org/a\nRUN true";

        assertThat(of(snippet).json()).doesNotContain("s3cret").doesNotContain("user:");
        assertThat(lines(snippet)).contains("RUN true");
    }

    @Test
    void aUrlsCredentialCutTwiceIsCoveredAndTheAddressAfterItStays() {
        final String snippet = "RUN curl https://user:\\\ns3cret\\\nmore@example.org/a";

        assertThat(lines(snippet))
                .containsExactly("RUN curl https://" + W + "\\", W + "\\", W + "@example.org/a");
    }

    @Test
    void aUrlsCredentialIsCoveredAndTheLineStaysAsWritten() {
        final String snippet =
                "RUN curl -f https://user:s3cret@example.org/a.tgz -o /tmp/a # keep me\n"
                        + "RUN apt-get install -y https://s3cret@example.org/b.deb maven\n"
                        + "RUN curl https://example.org/pkg@1.2/c.tgz";

        assertThat(lines(snippet))
                .containsExactly(
                        "RUN curl -f https://" + W + "@example.org/a.tgz -o /tmp/a # keep me",
                        "RUN apt-get install -y https://" + W + "@example.org/b.deb maven",
                        "RUN curl https://example.org/pkg@1.2/c.tgz");
        assertThat(of(snippet).json()).doesNotContain("s3cret");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://user:s3cret@example.org/agent.tgz",
                "s3cret token with spaces",
                "\"; rm -rf /",
                "not-a-version-but-a-token-of-40-letters",
            })
    void anAgentVersionThatIsNoVersionIsNotShown(final String version) {
        // The definition's field is free text; it is shown to whoever asks about the task.
        assertThat(of(null, version).agentVersion()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3", "v2.0.1-rc.1", "2.1.116+build.3", "0.9~beta"})
    void aVersionIsShown(final String version) {
        assertThat(of(null, version).agentVersion()).isEqualTo(version);
    }

    @Test
    void aRecordOverTheCapLosesLinesFromTheEndAndSaysSo() {
        final StringBuilder snippet = new StringBuilder("RUN apt-get install -y first\n");
        for (int number = 0; number < 4000; number++) {
            snippet.append("RUN echo line ").append(number).append(" ").append("x".repeat(60));
            snippet.append('\n');
        }

        final ImageSettings written = of(snippet.toString());

        assertThat(written.rendered().getBytes(StandardCharsets.UTF_8).length)
                .as("well under what podman was measured to take slowly")
                .isLessThanOrEqualTo(ImageSettings.MOST_RENDERED);
        assertThat(lines(snippet.toString()).getFirst())
                .as("what it keeps is what came first")
                .isEqualTo("RUN apt-get install -y first");
        assertThat(lines(snippet.toString()).getLast())
                .as("no reader takes it as whole")
                .contains("not recorded");
        assertThat(ImageSettings.parse(written.json())).contains(written);
    }

    @Test
    void oneLineOverTheCapCannotPushTheRecordOverIt() {
        final String snippet = "RUN apt-get install -y " + "p".repeat(200_000);

        final ImageSettings written = of(snippet);

        assertThat(written.rendered().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ImageSettings.MOST_RENDERED);
        assertThat(lines(snippet)).singleElement().asString().contains("not recorded");
        assertThat(written.packages()).as("said by the same line").isEmpty();
    }

    @Test
    void aLabelOverTheLimitForAReplySaysNothing() {
        // No Porelo wrote it, and an answer over the wire's limit would end the connection.
        final String padded =
                of("RUN true")
                        .json()
                        .replace(
                                "\"baseImage\":\"ubuntu:24.04\"",
                                "\"baseImage\":\""
                                        + "x".repeat(ImageSettings.MOST_READ + 1)
                                        + "\"");

        assertThat(ImageSettings.parse(padded)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not json",
                "[]",
                "{}",
                "{\"version\":2,\"baseImage\":\"a\"}",
                "{\"version\":1,\"baseImage\":3}"
            })
    void aLabelThisVersionDidNotWriteSaysNothing(final String label) {
        // A wrong answer here is a person trusting a tool that is not in the image.
        assertThat(ImageSettings.parse(label)).as("does not know, never a guess").isEmpty();
    }

    @Test
    void aListOfSomethingElseIsNotAList() {
        final String label =
                of("RUN true").json().replaceFirst("\"lines\":\\[\\{[^]]*\\}\\]", "\"lines\":[1]");
        assertThat(label).contains("\"lines\":[1]");

        assertThat(ImageSettings.parse(label)).isEmpty();
    }

    @Test
    void anImageWithoutAgentRecordsThat() {
        final ImageSettings none = ImageSettings.of(project(null), "a", List.of(), "b", "", "");

        assertThat(ImageSettings.parse(none.json())).contains(none);
        assertThat(none.lines()).isEqualTo(List.of());
    }
}
