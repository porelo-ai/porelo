package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.fuin.porelo.app.HostVaultOwner;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A decoding refusal must release the old share even if its vault file returns. */
class HostVaultKeepTest {
    @Test
    void badBase64CannotLeaveThePreviousShareUsable(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final var context =
                    new PoreloContext(
                                    new FakeCommandRunner(),
                                    new PoreloPaths(
                                            XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                    args -> 0)
                            .withHostVault(owner);
            context.vault().write(Map.of(), new byte[32]);
            owner.keep(new byte[32]);
            final Path original = Files.move(context.vault().path(), dir.resolve("original.bin"));
            context.vault().write(Map.of(), new byte[32]);

            assertThatThrownBy(
                            () ->
                                    VaultMethods.keepHostVaultShare(
                                            context,
                                            Map.of(
                                                    "vault",
                                                    context.vault().path().toString(),
                                                    "share",
                                                    "not base64!")))
                    .as("the daemon operation refuses the encoding before retaining anything")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(ex.getErrorName())
                                            .isEqualTo("org.fuin.porelo.Tasks1.Failed"));
            Files.move(original, context.vault().path(), StandardCopyOption.REPLACE_EXISTING);
            assertThat(owner.isOpen())
                    .as("restoring the original vault cannot revive a share after a refused keep")
                    .isFalse();
        }
    }
}
