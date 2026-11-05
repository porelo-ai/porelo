package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What an image records about its build: a person reads it to learn what a task can run. */
class ImageSettingsTest {

    private static ImageSettings of(final String snippet) {
        return ImageSettings.of(
                new Project("uc", "", SecurityClass.OFFLINE, "ubuntu:24.04", snippet),
                "base source",
                "lines source",
                "class source",
                "an-agent",
                "1.2.3");
    }

    @Test
    void whatIsRecordedReadsBackWhole() {
        final ImageSettings written =
                of("RUN apt-get install -y \"maven\"\nENV A=\"$B \\\"c\\\"\"\nRUN echo ü");

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
    void thePackagesAreWhatTheInstallCommandsName() {
        // What a person asks: is Maven in it, is Java in it.
        assertThat(
                        of("""
                                # apt-get install commented-out
                                RUN apt-get update && apt-get install -y --no-install-recommends \\
                                    maven openjdk-21-jdk=21.0.1 && rm -rf /var/lib/apt/lists/*
                                RUN dnf -y install gcc; apk add --no-cache 'jq'
                                RUN microdnf install $FROM_A_VARIABLE make | tee /dev/null
                                RUN curl -fsSL https://example.org/gradle.zip -o /tmp/gradle.zip
                                """)
                                .packages())
                .as("each install command's own words, and nothing of the commands around them")
                .containsExactly("maven", "openjdk-21-jdk=21.0.1", "gcc", "jq", "make");
    }

    @Test
    void aCommandThatOnlyMentionsAnInstallerNamesNoPackage() {
        assertThat(of("RUN echo apt-get is not used here\nRUN myapt install thing").packages())
                .isEmpty();
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
                of("RUN true").json().replace("\"lines\":[\"RUN true\"]", "\"lines\":[1]");
        assertThat(label).contains("\"lines\":[1]");

        assertThat(ImageSettings.parse(label)).isEmpty();
    }

    @Test
    void anImageWithoutAgentRecordsThat() {
        final ImageSettings none =
                ImageSettings.of(
                        new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null),
                        "a",
                        "",
                        "b",
                        "",
                        "");

        assertThat(ImageSettings.parse(none.json())).contains(none);
        assertThat(none.lines()).isEqualTo(List.of());
    }
}
