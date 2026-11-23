package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The time limits of the messages follow the account's four factors: at 2 each is twice its
 * default, at 1.0 it is its default.
 */
class MessagingLimitsTest {

    @TempDir Path dir;

    private PoreloContext context(final String limits) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0)
                .withAccount(AccountConfig.read(new StringReader(limits), "config.yml"));
    }

    private PoreloContext slow() {
        return context("limits:\n  services: 2\n  machines: 2\n  polling: 2\n  retries: 2\n");
    }

    private PoreloContext plain() {
        return context("limits:\n  services: 1.0\n");
    }

    @Test
    void theRoomSweepLooksHalfAsOftenAtPolling2() {
        final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-11-13T09:00:00Z"));
        final TaskRoomSweep sweep =
                new TaskRoomSweep(
                        slow(), TaskRoomSweep.INTERVAL, () -> false, line -> {}, now::get);

        assertThat(sweep.due()).as("at the start").isTrue();
        now.set(now.get().plus(TaskRoomSweep.INTERVAL).plusSeconds(1));
        assertThat(sweep.due()).as("a minute on, polling 2").isFalse();
        now.set(now.get().plus(TaskRoomSweep.INTERVAL));
        assertThat(sweep.due()).as("two minutes after the last").isTrue();

        final TaskRoomSweep plain =
                new TaskRoomSweep(
                        plain(), TaskRoomSweep.INTERVAL, () -> false, line -> {}, now::get);
        assertThat(plain.due()).isTrue();
        now.set(now.get().plus(TaskRoomSweep.INTERVAL));
        assertThat(plain.due()).as("a minute on, polling 1.0").isTrue();
    }

    @Test
    void aTransportLoopRestsTwiceAsLongAtRetries2() {
        assertThat(new MessageWatch(slow(), Duration.ZERO).rested())
                .isEqualTo(MessageWatch.IDLE.multipliedBy(2));
        assertThat(new MessageWatch(plain(), Duration.ZERO).rested()).isEqualTo(MessageWatch.IDLE);
    }

    @Test
    void closingWaitsTwiceAsLongForAPassAtMachines2() {
        assertThat(new MessageWatch(slow(), Duration.ZERO).closing())
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(new MessageWatch(plain(), Duration.ZERO).closing())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aWaitingPollHoldsTheServerOpenTwiceAsLongAtServices2() {
        final TransportDirectory transports = new TransportDirectory(List.of(dir));

        assertThat(new TransportConversations(slow(), transports).waitSeconds())
                .isEqualTo(2 * TransportConversations.WAIT_SECONDS);
        assertThat(new TransportConversations(plain(), transports).waitSeconds())
                .isEqualTo(TransportConversations.WAIT_SECONDS);
    }
}
