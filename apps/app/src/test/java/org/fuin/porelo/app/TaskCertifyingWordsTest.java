package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for what a refused start on a host says, which a person acts on. */
class TaskCertifyingWordsTest {

    @Test
    void aPersonIsToldTheWayFromTheirOwnComputer() {
        assertThat(
                        TaskCertifying.noCertificate(
                                "foo", "first", "alice", List.of("ssh-ed25519 AAAA")))
                .contains("porelo task start first --host alice@<this machine's address>")
                .contains("--project foo")
                .contains("On your own computer");
    }

    @Test
    void aProjectNamingNoCertifierIsSaidApart() {
        assertThat(TaskCertifying.noCertificate("foo", "first", "alice", List.of()))
                .contains("names no key that may certify it")
                .doesNotContain("--host");
    }
}
