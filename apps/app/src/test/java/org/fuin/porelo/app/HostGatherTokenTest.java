package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostGatherTokenTest {
    @Test
    void replacementAndExceptionalExitEraseBorrowedBuffers() {
        // Superseded and failed-issuance token buffers must not keep usable credentials.
        final HostGatherToken token = new HostGatherToken();
        char[] first = new char[0], second = new char[0];
        try (token) {
            token.accept("first-secret");
            first = token.value();
            token.accept("second-secret");
            second = token.value();
            throw new IllegalStateException("storage failed");
        } catch (IllegalStateException expected) {
            assertThat(first).containsOnly((char) 0);
            assertThat(second).containsOnly((char) 0);
        }
    }
}
