package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/**
 * Tests what is done without asking where a keyring did not answer, or a kept passphrase no longer
 * opens the vault.
 */
class KeyringRetryTest {

    @Test
    void aKeyringThatDidNotAnswerIsAskedAgainBeforeItIsSaid() {
        final ArrayDeque<KernelKeyring.Forgotten> answers =
                new ArrayDeque<>(
                        List.of(KernelKeyring.Forgotten.UNKNOWN, KernelKeyring.Forgotten.CLEARED));

        assertThat(VaultLockCommand.forgetRetried(answers::poll, LimitFactors.DEFAULT))
                .isEqualTo(KernelKeyring.Forgotten.CLEARED);

        final ArrayDeque<KernelKeyring.Forgotten> never =
                new ArrayDeque<>(
                        List.of(
                                KernelKeyring.Forgotten.UNKNOWN,
                                KernelKeyring.Forgotten.UNKNOWN,
                                KernelKeyring.Forgotten.UNKNOWN,
                                KernelKeyring.Forgotten.CLEARED));
        assertThat(VaultLockCommand.forgetRetried(never::poll, LimitFactors.DEFAULT))
                .as("three tries, then it is said")
                .isEqualTo(KernelKeyring.Forgotten.UNKNOWN);
    }

    @Test
    void aKeyringThatNeverAnswersIsAskedThreeTimesTimesTheRetriesFactor() {
        final int[] asked = {0};
        final java.util.function.Supplier<KernelKeyring.Forgotten> never =
                () -> {
                    asked[0]++;
                    return KernelKeyring.Forgotten.UNKNOWN;
                };

        VaultLockCommand.forgetRetried(never, LimitFactors.DEFAULT);
        assertThat(asked[0]).as("at the factor 1.0").isEqualTo(3);

        asked[0] = 0;
        VaultLockCommand.forgetRetried(never, LimitFactors.parse("retries=2"));
        assertThat(asked[0]).as("at 2.0").isEqualTo(6);
    }

    @Test
    void aKeptPassphraseThatNoLongerOpensTheVaultIsStale() {
        assertThat(PoreloContext.stale(Optional.of("old".toCharArray()), typed -> false)).isTrue();
        assertThat(PoreloContext.stale(Optional.of("right".toCharArray()), typed -> true))
                .isFalse();
        assertThat(PoreloContext.stale(Optional.empty(), typed -> false))
                .as("nothing kept")
                .isFalse();
    }
}
