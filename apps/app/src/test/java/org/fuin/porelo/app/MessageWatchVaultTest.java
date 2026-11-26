package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Messages that waited for the vault go once it is open, not at the next pass. */
class MessageWatchVaultTest {

    @TempDir Path dir;

    @Test
    void aPassThatWaitedForTheVaultIsRunAgainOnceTheVaultOpensAndOnlyOnce() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new TransportTestRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        final boolean[] open = {false};
        final MessageWatch watch = new MessageWatch(context, Duration.ofMinutes(1), () -> open[0]);
        final AtomicInteger passes = new AtomicInteger();

        assertThat(watch.afterTheVault(passes::incrementAndGet)).as("nothing waited").isFalse();
        watch.waitedForTheVault();
        assertThat(watch.afterTheVault(passes::incrementAndGet))
                .as("the vault still shut")
                .isFalse();

        open[0] = true;
        assertThat(watch.afterTheVault(passes::incrementAndGet)).isTrue();
        assertThat(watch.afterTheVault(passes::incrementAndGet)).as("once").isFalse();
        assertThat(passes).hasValue(1);
    }

    @Test
    void theVaultOpeningIsSeenOnceEachTimeAndAtTheFirstLookWhenItIsOpenThen() {
        // After a restart the vault was unlocked and the first message waited for the first timed
        // pass: 64 s.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final PoreloContext context =
                new PoreloContext(
                        new TransportTestRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        final boolean[] open = {true};
        final MessageWatch watch = new MessageWatch(context, Duration.ofMinutes(1), () -> open[0]);

        assertThat(watch.opened()).as("open at the first look").isTrue();
        assertThat(watch.opened()).as("still open").isFalse();
        open[0] = false;
        assertThat(watch.opened()).as("shut").isFalse();
        open[0] = true;
        assertThat(watch.opened()).as("opened again").isTrue();
        assertThat(watch.opened()).isFalse();
    }

    @Test
    void theLogSaysOnceWhileShutThatMessagesWaitAndAgainOnlyAfterTheVaultOpenedAndShutAgain()
            throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new TransportTestRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        java.nio.file.Files.createDirectories(context.vault().path().getParent());
        context.vault().write(java.util.Map.of(), "fixture passphrase".toCharArray());
        final Path outbox =
                context.paths().messaging().mailbox("porelo-demo-1").resolve("box/outbox/new");
        java.nio.file.Files.createDirectories(outbox);
        java.nio.file.Files.writeString(outbox.resolve("m1.json"), "{}");
        final boolean[] open = {false};
        final MessageWatch watch = new MessageWatch(context, Duration.ofMinutes(1), () -> open[0]);
        final java.io.PrintStream before = System.err;
        final java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(log, true));
        try {
            watch.moveOnce();
            watch.moveOnce();
            assertThat(log.toString())
                    .as("said once over two shut passes with a message waiting")
                    .containsOnlyOnce("messages wait for the Vault-Passphrase");
            open[0] = true;
            watch.moveOnce();
            open[0] = false;
            watch.moveOnce();
            assertThat(log.toString())
                    .as("said again after the vault opened and shut again")
                    .containsSubsequence(
                            "messages wait for the Vault-Passphrase",
                            "messages wait for the Vault-Passphrase");
        } finally {
            System.setErr(before);
        }
    }

    @Test
    void theLogSaysThatASignedMessageWaitsInTheInboundDirectoryWhileTheVaultIsShut()
            throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new TransportTestRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        java.nio.file.Files.createDirectories(context.vault().path().getParent());
        context.vault().write(java.util.Map.of(), "fixture passphrase".toCharArray());
        final Path inbound =
                new Mailbox(context.paths().messaging().mailbox("porelo-demo-1")).inbound();
        java.nio.file.Files.createDirectories(inbound);
        java.nio.file.Files.writeString(inbound.resolve("m1.json"), "{}");
        java.nio.file.Files.writeString(inbound.resolve("m1.json.sig"), "sig");
        final MessageWatch watch = new MessageWatch(context, Duration.ofMinutes(1), () -> false);
        final java.io.PrintStream before = System.err;
        final java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(log, true));
        try {
            watch.moveOnce();
            assertThat(log.toString())
                    .as("an arrived signed message waits for the vault too")
                    .contains("messages wait for the Vault-Passphrase");
        } finally {
            System.setErr(before);
        }
    }
}
