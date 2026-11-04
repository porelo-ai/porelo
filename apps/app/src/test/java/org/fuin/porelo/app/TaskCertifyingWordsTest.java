package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for what a refused start on a host says, which a person acts on. */
class TaskCertifyingWordsTest {

    private static final List<String> A_KEY = List.of("ssh-ed25519 AAAA");

    @Test
    void aPersonIsToldTheWayFromTheirOwnComputerWithAllItNeeds() {
        assertThat(TaskCertifying.noCertificate("foo", "first", "alice", A_KEY, "bar"))
                .as("a start with --host and no repository is refused on the host")
                .contains(
                        "porelo task start first --host alice@<this machine's address>"
                                + " --project foo --repository bar")
                .contains("On your own computer");
    }

    @Test
    void aProjectNamingNoCertifierIsSaidApart() {
        assertThat(TaskCertifying.noCertificate("foo", "first", "alice", List.of(), "bar"))
                .as("no way from any computer helps until a key is named")
                .contains("names no key that may certify it")
                .doesNotContain("--host");
    }

    @Test
    void aTaskWithNoProjectHasNoAccessFileToBlame() {
        assertThat(
                        TaskCertifying.noCertificate(
                                TaskCertification.NO_PROJECT, "first", "alice", List.of(), "bar"))
                .as("its starter's own key certifies it")
                .doesNotContain("access file")
                .contains("--host alice@");
    }
}
