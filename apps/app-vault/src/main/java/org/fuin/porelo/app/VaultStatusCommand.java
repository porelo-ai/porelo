package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Says whether this account's vault is open, shut or not there, for a program as much as for a
 * person.
 *
 * <p>The answer is the exit code, so a playbook branches on it without reading words meant for a
 * person: 0 open, 3 shut, 4 no vault. They stay clear of 64, 65 and 70, which mean a refusal.
 * "Open" is proved, not assumed: what this account keeps, a cached passphrase or a device's share,
 * has to read the vault, because a kept passphrase that no longer opens it would let a play go on
 * to a vault that refuses its first read.
 */
@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description =
                "Says whether the vault is open, shut or not there. Exit 0 open, 3 shut, 4 no vault.")
public class VaultStatusCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The vault opens with what this account keeps. */
    static final int OPEN = 0;

    /** The vault is there and nothing this account keeps opens it. */
    static final int SHUT = 3;

    /** There is no vault. */
    static final int NONE = 4;

    @Option(
            names = "--json",
            description = "Prints the answer as one JSON object: exists, open and path.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final VaultFile vault = context.vault();

        if (!vault.exists()) {
            return answer(out, false, false, "none      there is no vault at " + vault.path());
        }
        try {
            // Readable while shut, and it fails on a file that is not a vault at all: that is a
            // broken vault, not a shut one, and a play must not be told to ask for a passphrase.
            vault.slots();
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        final Optional<VaultFile.Opener> kept = context.opener();
        if (kept.isEmpty()) {
            return answer(
                    out, true, false, "shut      nothing this account keeps opens " + vault.path());
        }
        try {
            vault.read(kept.get());
        } catch (VaultException ex) {
            return answer(
                    out,
                    true,
                    false,
                    "shut      what this account keeps no longer opens " + vault.path());
        }
        return answer(out, true, true, "open      " + vault.path());
    }

    private int answer(PrintWriter out, boolean exists, boolean open, String line) {
        if (json) {
            final Map<String, Object> report = new LinkedHashMap<>();
            report.put("exists", exists);
            report.put("open", open);
            report.put("path", context.vault().path().toString());
            out.println(org.fuin.porelo.wire.Json.write(report));
        } else {
            out.println(line);
        }
        out.flush();
        if (open) {
            return OPEN;
        }
        return exists ? SHUT : NONE;
    }
}
