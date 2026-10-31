package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.fuin.porelo.app.HostVaultOwner;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the authorization boundary on the actual forwarded interface socket. */
@Timeout(20)
class HostVaultSocketTest {
    private static final String API = "org.fuin.porelo.Tasks1.";

    @Test
    void aBadBase64KeepReleasesThePreviousUnlockAfterAReplacement(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        context.vault().write(Map.of(), new byte[32]);
        final Path saved = dir.resolve("saved.bin");
        try (VarlinkServer server = PoreloDaemon.serving(context, context.paths().daemonSocket())) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                context.hostVault().keep(new byte[32]);
                Files.move(context.vault().path(), saved);
                context.vault().write(Map.of(), new byte[32]);
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                API + "KeepHostVaultShare",
                                                Map.of(
                                                        "vault",
                                                        context.vault().path().toString(),
                                                        "share",
                                                        "not base64!")))
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex -> assertThat(ex.getErrorName()).isEqualTo(API + "Failed"));
                Files.move(
                        saved,
                        context.vault().path(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertThat(context.hostVault().isOpen())
                        .as("decoding refusal releases the previous share even if its file returns")
                        .isFalse();
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void aMixedVaultRefusesItsSshShareThroughUnlockWithShare(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        makeDeviceVault(context);
        addLegacyHostSlot(context.vault());
        final byte[] before = Files.readAllBytes(context.vault().path());
        try (VarlinkServer server = PoreloDaemon.serving(context, context.paths().daemonSocket())) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                try {
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    API + "UnlockWithShare",
                                                    Map.of(
                                                            "share",
                                                            Base64.getEncoder()
                                                                    .encodeToString(sshShare()))))
                            .as("an SSH share cannot be cached as a device unlock")
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(API + "NotAHostVault"));
                    assertThat(org.fuin.porelo.app.VaultShare.held(context.paths())).isEmpty();
                    assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
                } finally {
                    org.fuin.porelo.app.VaultShare.forget(context.paths());
                }
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"device", "mixed"})
    void nonHostSlotsNeverReadTheHostOwner(String kind, @TempDir Path dir) throws Exception {
        final PoreloContext base = context(dir);
        makeDeviceVault(base);
        if (kind.equals("mixed")) addLegacyHostSlot(base.vault());
        final var access =
                new org.fuin.porelo.app.HostVaultAccess() {
                    public void keep(byte[] share) {
                        throw new AssertionError("not a host vault");
                    }

                    public boolean isOpen() {
                        throw new AssertionError("not a host vault");
                    }

                    public byte[] share() {
                        throw new AssertionError("not a host vault");
                    }

                    public boolean forget() {
                        return false;
                    }
                };
        final PoreloContext context = base.withHostVault(access);
        for (var slot : context.vault().slots()) {
            assertThat(PoreloDaemon.slotAsMap(slot, context)).containsEntry("self", false);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "device,Keyslots",
        "device,RevokeKeyslot",
        "device,UnlockWithShare",
        "mixed,Keyslots",
        "mixed,RevokeKeyslot",
        "mixed,UnlockWithShare"
    })
    void deviceSharesRemainSelfInEveryKeyslotReply(String kind, String method, @TempDir Path dir)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "device self reporting requires a kernel keyring");
        final PoreloContext context = context(dir);
        makeDeviceVault(context);
        if (kind.equals("mixed")) addLegacyHostSlot(context.vault());
        try {
            org.fuin.porelo.app.VaultShare.keep(
                    context.paths(), new byte[32], java.time.Duration.ofMinutes(1));
            try (VarlinkServer server =
                    PoreloDaemon.serving(context, context.paths().daemonSocket())) {
                final Thread serving = Thread.ofPlatform().start(server);
                try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                    final var answer =
                            client.call(
                                    API + method,
                                    Map.of(
                                            "id",
                                            "absent-slot",
                                            "share",
                                            Base64.getEncoder().encodeToString(new byte[32])));
                    final java.util.List<?> slots =
                            switch (method) {
                                case "Keyslots" -> (java.util.List<?>) answer.get("slots");
                                case "RevokeKeyslot" -> (java.util.List<?>) answer.get("remaining");
                                default -> java.util.List.of(answer.get("slot"));
                            };
                    assertThat(slots)
                            .as("%s reports the device share held in the keyring", method)
                            .anySatisfy(
                                    slot ->
                                            assertThat(((Map<?, ?>) slot).get("self"))
                                                    .isEqualTo(true));
                } finally {
                    server.close();
                    serving.join();
                }
            }
        } finally {
            org.fuin.porelo.app.VaultShare.forget(context.paths());
        }
    }

    private static void makeDeviceVault(PoreloContext context) {
        final char[] passphrase = "test-passphrase".toCharArray();
        context.vault().write(Map.of(), passphrase);
        context.vault()
                .enroll(
                        org.fuin.porelo.vault.VaultFile.Opener.passphrase(passphrase),
                        new byte[32],
                        "device",
                        "USER_SCOPED");
        context.vault()
                .revoke(org.fuin.porelo.vault.VaultFile.Opener.share(new byte[32]), "passphrase");
    }

    private static byte[] sshShare() {
        final byte[] share = new byte[32];
        java.util.Arrays.fill(share, (byte) 71);
        return share;
    }

    @Test
    void aFailedSocketKeepReleasesTheShareForAMalformedReplacement(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final byte[] share = new byte[32];
        context.vault().write(Map.of(), share);
        final Path saved = dir.resolve("saved.bin");
        try (VarlinkServer server = PoreloDaemon.serving(context, context.paths().daemonSocket())) {
            final Thread serving = Thread.ofPlatform().start(server);
            try {
                context.hostVault().keep(share);
                Files.move(context.vault().path(), saved);
                Files.write(context.vault().path(), new byte[] {1, 2, 3});
                assertThatThrownBy(() -> context.hostVault().keep(share))
                        .isInstanceOf(org.fuin.porelo.vault.VaultException.class);
                Files.move(
                        saved,
                        context.vault().path(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertThat(context.hostVault().isOpen())
                        .as("a failed keep cannot revive the previous unlock")
                        .isFalse();
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"mixed", "device"})
    void nonHostVaultKindsHaveTheSameNamedRefusalOverTheSocket(String kind, @TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final char[] passphrase = "test-passphrase".toCharArray();
        context.vault().write(Map.of(), passphrase);
        context.vault()
                .enroll(
                        org.fuin.porelo.vault.VaultFile.Opener.passphrase(passphrase),
                        new byte[32],
                        "device",
                        "USER_SCOPED");
        context.vault()
                .revoke(org.fuin.porelo.vault.VaultFile.Opener.share(new byte[32]), "passphrase");
        if (kind.equals("mixed")) addLegacyHostSlot(context.vault());
        final byte[] before = Files.readAllBytes(context.vault().path());
        try (VarlinkServer server = PoreloDaemon.serving(context, context.paths().daemonSocket())) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                API + "KeepHostVaultShare",
                                                Map.of(
                                                        "vault",
                                                        context.vault().path().toString(),
                                                        "share",
                                                        Base64.getEncoder()
                                                                .encodeToString(new byte[32]))))
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex ->
                                        assertThat(ex.getErrorName())
                                                .isEqualTo(API + "NotAHostVault"));
                assertThatThrownBy(() -> context.hostVault().keep(new byte[32]))
                        .isInstanceOf(org.fuin.porelo.vault.NotAHostVaultException.class);
                final var cli =
                        org.fuin.porelo.app.PoreloCli.commandLine(
                                context.reading(
                                        new org.fuin.porelo.app.StandardInput(
                                                new java.io.ByteArrayInputStream(new byte[32]),
                                                false)));
                final var out = new java.io.StringWriter();
                cli.setOut(new java.io.PrintWriter(out));
                assertThat(cli.execute("vault", "unlock", "--share-stdin", "--json")).isEqualTo(65);
                assertThat(org.fuin.porelo.wire.Json.parse(out.toString()))
                        .isEqualTo(Map.of("vault", "not-a-host-vault"));
                assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    private static void addLegacyHostSlot(org.fuin.porelo.vault.VaultFile vault) throws Exception {
        final var observer = org.fuin.porelo.vault.VaultFile.class.getDeclaredField("unwrapped");
        observer.setAccessible(true);
        final Object previous = observer.get(null);
        final var master = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        try {
            observer.set(
                    null, (java.util.function.Consumer<byte[]>) value -> master.set(value.clone()));
            vault.read(new byte[32]);
        } finally {
            observer.set(null, previous);
        }
        try {
            final var headerMethod = vault.getClass().getDeclaredMethod("header");
            headerMethod.setAccessible(true);
            final Object header = headerMethod.invoke(vault);
            final var slotsMethod = header.getClass().getDeclaredMethod("slots");
            slotsMethod.setAccessible(true);
            final java.util.List<Object> slots =
                    new java.util.ArrayList<>((java.util.List<?>) slotsMethod.invoke(header));
            final var slotMethod =
                    vault.getClass().getDeclaredMethod("sshAgentSlot", byte[].class, byte[].class);
            slotMethod.setAccessible(true);
            slots.add(slotMethod.invoke(vault, sshShare(), master.get()));
            final var writer =
                    vault.getClass()
                            .getDeclaredMethod(
                                    "writeContent", Map.class, java.util.List.class, byte[].class);
            writer.setAccessible(true);
            writer.invoke(vault, Map.of(), slots, master.get());
            assertThat(vault.read(new byte[32])).isEmpty();
        } finally {
            java.util.Arrays.fill(master.get(), (byte) 0);
        }
    }

    @Test
    void theHostConnectionScopesEveryCallAndCannotReadAfterForget(@TempDir Path dir)
            throws Exception {
        final PoreloContext clientContext = context(dir);
        final byte[] share = new byte[32];
        java.util.Arrays.fill(share, (byte) 21);
        clientContext.vault().write(Map.of(), share);
        try (VarlinkServer server =
                PoreloDaemon.serving(
                        clientContext,
                        clientContext.paths().daemonSocket(),
                        client -> new VarlinkServer.Peer(1000, 123, true))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try {
                clientContext.hostVault().keep(share);
                assertThat(clientContext.hostVault().isOpen())
                        .as("the connection asks its vault's state")
                        .isTrue();
                final byte[] read = clientContext.hostVault().share();
                try {
                    assertThat(java.util.Arrays.equals(read, share))
                            .as("the connection reads its vault's share")
                            .isTrue();
                } finally {
                    java.util.Arrays.fill(read, (byte) 0);
                }
                assertThat(clientContext.hostVault().forget())
                        .as("the connection forgets that same vault")
                        .isTrue();
                assertThat(clientContext.hostVault().isOpen())
                        .as("forget is effective immediately")
                        .isFalse();
                assertThatThrownBy(() -> clientContext.hostVault().share())
                        .as("a stale connection cannot retrieve a released share")
                        .isInstanceOf(org.fuin.porelo.vault.VaultException.class);
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void aSlotTakesItsHeldShareInOneOwnerTransaction(@TempDir Path dir) {
        final PoreloContext base = context(dir);
        base.vault().write(Map.of(), new byte[32]);
        final var access =
                new org.fuin.porelo.app.HostVaultAccess() {
                    public void keep(byte[] share) {}

                    public boolean isOpen() {
                        return true;
                    }

                    public byte[] share() {
                        assertThat(Thread.holdsLock(this))
                                .as("lock cannot interleave the state and copy")
                                .isTrue();
                        return new byte[32];
                    }

                    public boolean forget() {
                        return false;
                    }
                };
        assertThat(
                        PoreloDaemon.slotAsMap(
                                base.vault().slots().getFirst(), base.withHostVault(access)))
                .as("the slot reports the atomically held share")
                .containsEntry("self", true);
    }

    @Test
    void everyProcessOfTheAccountCanReadTheShare(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        context.vault().write(Map.of(), new byte[32]);
        try (VarlinkServer server =
                PoreloDaemon.serving(
                        context,
                        dir.resolve("daemon.sock"),
                        client -> new VarlinkServer.Peer(1000, 123, true))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final var scope =
                        Map.<String, Object>of("vault", context.vault().path().toString());
                client.call(
                        API + "KeepHostVaultShare",
                        Map.of(
                                "vault",
                                scope.get("vault"),
                                "share",
                                Base64.getEncoder().encodeToString(new byte[32])));
                assertThat(client.call(API + "ReadHostVaultShare", scope))
                        .as("the account uid authorizes a local or SSH process")
                        .containsKey("share");
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "HostVaultShare",
                "KeepHostVaultShare",
                "ReadHostVaultShare",
                "ForgetHostVaultShare",
                "Keyslots",
                "EnrollDevice",
                "UnlockWithShare",
                "RevokeKeyslot",
                "Lock",
                "VaultClear"
            })
    void anotherUidCannotReachAVaultMethod(String method, @TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        context.vault().write(Map.of(), new byte[32]);
        final byte[] before = Files.readAllBytes(context.vault().path());
        try (VarlinkServer server =
                PoreloDaemon.serving(
                        context,
                        dir.resolve("daemon.sock"),
                        client -> new VarlinkServer.Peer(2000, 123, false))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                API + method,
                                                Map.of(
                                                        "vault",
                                                        context.vault().path().toString(),
                                                        "share",
                                                        Base64.getEncoder()
                                                                .encodeToString(new byte[32]),
                                                        "id",
                                                        "ssh-agent",
                                                        "name",
                                                        "device",
                                                        "storage",
                                                        "USER_SCOPED",
                                                        "force",
                                                        true)))
                        .as("another uid cannot read or mutate via %s", method)
                        .isInstanceOfAny(java.io.IOException.class, VarlinkException.class);
                assertThat(Files.readAllBytes(context.vault().path()))
                        .as("a refused caller changes no slots or entries")
                        .isEqualTo(before);
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void anotherKeyKeepsItsExitCodeThroughTheRealDaemonSocket(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        context.vault()
                .write(
                        Map.of("service", org.fuin.porelo.vault.VaultEntry.of("example-value")),
                        new byte[32]);
        final byte[] before = Files.readAllBytes(context.vault().path());
        final byte[] wrong = new byte[32];
        java.util.Arrays.fill(wrong, (byte) 71);
        try (VarlinkServer server =
                PoreloDaemon.serving(
                        context,
                        context.paths().daemonSocket(),
                        client -> new VarlinkServer.Peer(1000, 123, true))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try {
                final var cli =
                        org.fuin.porelo.app.PoreloCli.commandLine(
                                context.reading(
                                        new org.fuin.porelo.app.StandardInput(
                                                new java.io.ByteArrayInputStream(wrong), false)));
                final var out = new java.io.StringWriter();
                cli.setOut(new java.io.PrintWriter(out));
                assertThat(cli.execute("vault", "unlock", "--share-stdin", "--json"))
                        .as("a daemon rejection remains an other-key outcome")
                        .isEqualTo(77);
                assertThat(org.fuin.porelo.wire.Json.parse(out.toString()))
                        .as("scripts receive a stable outcome")
                        .isEqualTo(Map.of("vault", "other-key"));
                assertThat(Files.readAllBytes(context.vault().path()))
                        .as("rejection never resets the vault")
                        .isEqualTo(before);
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void everyScopedMethodRefusesAnotherVaultAndAcceptsItsRealPath(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final byte[] share = new byte[32];
        context.vault().write(Map.of(), share);
        final Path other = dir.resolve("other.bin");
        new org.fuin.porelo.vault.VaultFile(other).write(Map.of(), new byte[32]);
        final Path alias = dir.resolve("alias.bin");
        Files.createSymbolicLink(alias, context.vault().path());
        try (VarlinkServer server =
                PoreloDaemon.serving(
                        context,
                        dir.resolve("daemon.sock"),
                        client -> new VarlinkServer.Peer(1000, 123, true))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> own = Map.of("vault", alias.toString());
                final String encoded = Base64.getEncoder().encodeToString(share);
                client.call(
                        API + "KeepHostVaultShare",
                        Map.of("vault", alias.toString(), "share", encoded));
                for (String method :
                        new String[] {
                            "KeepHostVaultShare",
                            "ReadHostVaultShare",
                            "UnlockWithShare",
                            "Lock",
                            "VaultClear"
                        }) {
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    API + method,
                                                    Map.of(
                                                            "vault",
                                                            other.toString(),
                                                            "share",
                                                            encoded,
                                                            "force",
                                                            true)))
                            .as("%s must check its vault argument", method)
                            .isInstanceOf(VarlinkException.class);
                    assertThat(client.call(API + "HostVaultShare", own))
                            .as("refusing %s leaves the owner open", method)
                            .containsEntry("open", true);
                }
                assertThat(client.call(API + "HostVaultShare", Map.of("vault", other.toString())))
                        .as("another vault never sees this owner's state")
                        .containsEntry("open", false);
                assertThat(
                                client.call(
                                        API + "ForgetHostVaultShare",
                                        Map.of("vault", other.toString())))
                        .as("another vault cannot forget this share")
                        .containsEntry("wasHeld", false);
                Files.move(
                        other,
                        context.vault().path(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertThat(client.call(API + "HostVaultShare", own))
                        .as("replacing the file changes its wrapped-key identity")
                        .containsEntry("open", false);
                assertThatThrownBy(() -> client.call(API + "ReadHostVaultShare", own))
                        .as("the earlier share cannot be released for a replacement vault")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex -> assertThat(ex.getParameters()).doesNotContainKey("share"));
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void theProductionPeerReaderAcceptsTheCurrentUid(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        context.vault().write(Map.of(), new byte[32]);
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("daemon.sock"))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final var scope =
                        Map.<String, Object>of("vault", context.vault().path().toString());
                client.call(
                        API + "KeepHostVaultShare",
                        Map.of(
                                "vault",
                                scope.get("vault"),
                                "share",
                                Base64.getEncoder().encodeToString(new byte[32])));
                assertThat(client.call(API + "ReadHostVaultShare", scope))
                        .as("SO_PEERCRED recognizes this account")
                        .containsKey("share");
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void theDaemonAndItsWatchesShareTheInProcessOwner(@TempDir Path dir) {
        try (var watches =
                PoreloDaemon.watches(
                        context(dir),
                        java.time.Duration.ZERO,
                        java.time.Duration.ZERO,
                        java.time.Duration.ZERO)) {
            final PoreloContext context = watches.context();
            assertThat(context.hostVault())
                    .as("startup creates one in-process owner")
                    .isInstanceOf(HostVaultOwner.class);
            context.vault().write(Map.of(), new byte[32]);
            context.hostVault().keep(new byte[32]);
            for (Object watch :
                    new Object[] {watches.upstream(), watches.mail(), watches.configuration()}) {
                final var field = watch.getClass().getDeclaredField("context");
                field.setAccessible(true);
                final PoreloContext actual = (PoreloContext) field.get(watch);
                assertThat(actual)
                        .as("%s receives the startup context", watch.getClass().getSimpleName())
                        .isSameAs(context);
                assertThat(actual.vault().read(actual.requireOpener()))
                        .as("each watch opens through the shared owner with no socket")
                        .isEmpty();
            }
            final Class<?> cache = Class.forName("org.fuin.porelo.app.VaultCache");
            final var hold = cache.getDeclaredMethod("hold");
            final var stop = cache.getDeclaredMethod("stop");
            final var read =
                    cache.getDeclaredMethod(
                            "read",
                            org.fuin.porelo.vault.VaultFile.class,
                            org.fuin.porelo.vault.VaultFile.Opener.class);
            final var kept = cache.getDeclaredField("kept");
            hold.setAccessible(true);
            stop.setAccessible(true);
            read.setAccessible(true);
            kept.setAccessible(true);
            hold.invoke(null);
            try {
                read.invoke(null, context.vault(), context.requireOpener());
                assertThat(kept.get(null))
                        .as("the actual daemon owner has cached entries to drop")
                        .isNotNull();
                context.hostVault().forget();
                assertThat(kept.get(null))
                        .as("startup wires the owner's cache invalidation callback")
                        .isNull();
            } finally {
                stop.invoke(null);
            }
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private PoreloContext context(Path dir) {
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(
                        new XdgPaths(
                                dir.resolve("config"),
                                dir.resolve("data"),
                                dir.resolve("state"),
                                dir.resolve("run"),
                                dir),
                        dir.resolve("bin")),
                args -> 0);
    }
}
