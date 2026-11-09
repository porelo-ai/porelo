package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostUidTest {
    @Test
    void theRealUidIsReadInsteadOfTheEffectiveOrSavedUid() throws Exception {
        assertThat(HostUid.parse("Name:\tporelo-host\nUid:\t1000\t0\t0\t0\nGid:\t1000\n"))
                .as("effective root cannot replace proof of the real uid")
                .isEqualTo(1000);
        assertThat(HostUid.parse("Uid:\t0\t0\t0\t0\n"))
                .as("real root is identified from the kernel record")
                .isZero();
    }

    @Test
    void onlyTheHostsOwnUserNamespaceCountsAsRoot() {
        assertThat(HostUid.initialNamespace("         0          0 4294967295\n"))
                .as("the host's own namespace maps every uid to itself")
                .isTrue();
        // unshare -Ur: "uid 0" is the caller; nested, the map can even read 0 0 1.
        assertThat(HostUid.initialNamespace("         0       1000          1\n")).isFalse();
        assertThat(HostUid.initialNamespace("         0          0          1\n")).isFalse();
        assertThat(HostUid.initialNamespace("0 0 1000\n1000 1000 4294966295\n")).isFalse();
        assertThat(HostUid.initialNamespace("")).isFalse();
    }

    @Test
    void thisTestRunsInTheHostsOwnNamespaceWhereTheMapIsReadable() throws Exception {
        // HostUid.real() itself, on this machine: it must answer, not refuse, outside a namespace.
        assertThat(HostUid.real())
                .isEqualTo(new com.sun.security.auth.module.UnixSystem().getUid());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Uid:\t0\n",
                "Uid: -1 0 0 0",
                "Uid: invalid 0 0 0",
                "Uid: 99999999999999999999 0 0 0"
            })
    void anAbsentOrMalformedUidIsRefused(String status) {
        // Unknown process identity must never default to root.
        assertThatThrownBy(() -> HostUid.parse(status))
                .as("only a kernel uid record proves the real uid")
                .isInstanceOf(IOException.class);
    }
}
