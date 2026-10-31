package org.fuin.porelo.app;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.NotAHostVaultException;
import org.fuin.porelo.vault.OtherVaultKeyException;
import org.fuin.porelo.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Changes the host's opening share without losing credentials or adding another way in. */
@Command(
        name = "rekey",
        mixinStandardHelpOptions = true,
        description = "Changes a host key using the previous share, preserving all vault entries.")
public final class VaultRekeyCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @Option(
            names = "--share-stdin",
            description = "Reads the old 32 bytes, then the new 32 bytes and end of input.")
    private boolean shareStdin;

    @Option(names = "--json", description = "Reports the vault outcome as one JSON object.")
    private boolean json;

    @Spec private CommandSpec spec;
    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final var err = spec.commandLine().getErr();
        if (!shareStdin || context.input().terminal()) {
            err.println(
                    "porelo: use --share-stdin with a pipe, never a terminal, carrying the old and new shares");
            err.flush();
            return 64;
        }
        byte[] input = null;
        byte[] old = null;
        byte[] fresh = null;
        try {
            input = VaultStdin.shares(context, 2);
            old = Arrays.copyOfRange(input, 0, 32);
            fresh = Arrays.copyOfRange(input, 32, 64);
            if (!context.vault().exists()) return answer("absent", 66);
            if (!context.vault().isHostVault()) throw new NotAHostVaultException();
            context.vault().rekey(old, fresh);
            context.hostVault().keep(fresh);
            return answer("open", 0);
        } catch (NotAHostVaultException ex) {
            err.println("porelo: " + ex.getMessage());
            return answer("not-a-host-vault", 65);
        } catch (OtherVaultKeyException ex) {
            if (!json)
                err.println(
                        "porelo: the previous key does not open this host vault; nothing was changed");
            return answer("other-key", 77);
        } catch (IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            return 64;
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            return 70;
        } finally {
            if (input != null) Arrays.fill(input, (byte) 0);
            if (old != null) Arrays.fill(old, (byte) 0);
            if (fresh != null) Arrays.fill(fresh, (byte) 0);
            err.flush();
        }
    }

    private int answer(String status, int exit) {
        final var out = spec.commandLine().getOut();
        if (json) out.println(org.fuin.porelo.wire.Json.write(Map.of("vault", status)));
        else if (exit == 0) out.println("unlocked  host key changed; all vault entries were kept");
        else if (exit == 66)
            spec.commandLine().getErr().println("porelo: there is no host vault to rekey");
        out.flush();
        return exit;
    }
}
