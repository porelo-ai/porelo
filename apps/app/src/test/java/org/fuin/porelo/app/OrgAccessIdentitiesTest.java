package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.organization.IdentitySource;
import org.junit.jupiter.api.Test;

/** The identity source a real read wraps, as the forge's directory is made only when asked. */
class OrgAccessIdentitiesTest {

    private final AtomicInteger opened = new AtomicInteger();

    private IdentitySource wrapped() {
        return OrgAccess.lazy(
                () -> {
                    opened.incrementAndGet();
                    return new IdentitySource() {
                        @Override
                        public Optional<Keys> keys(final String login) {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<Boolean> owns(
                                final String organisation, final String login) {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<String> cannotSay() {
                            return Optional.of("rate limited");
                        }
                    };
                });
    }

    @Test
    void theWrapperSaysWhyTheSourceCouldNotSay() {
        final IdentitySource identities = wrapped();

        identities.keys("alice");

        // Lost here, a person was never told the forge had rate limited the read.
        assertThat(identities.cannotSay()).contains("rate limited");
    }

    @Test
    void askingWhyBeforeAnyQuestionOpensNothing() {
        final IdentitySource identities = wrapped();

        assertThat(identities.cannotSay()).isEmpty();
        assertThat(opened).hasValue(0);
    }
}
