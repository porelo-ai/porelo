package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows what a host offers, and records the one key a person confirms.
 *
 * <p><strong>Two commands in one on purpose.</strong> Without a fingerprint it shows what the host
 * offers and records nothing - which is the half somebody needs in front of them while they check
 * it against what they were told. With one it records that key and only if the host still offers
 * it, which is what stops a key that arrived between being shown and being confirmed from being the
 * one that is written.
 *
 * <p>Accepting on first use would be easier and is what this refuses to do: it is also exactly what
 * an interception looks like.
 */
@Command(
        name = "trust-host",
        mixinStandardHelpOptions = true,
        description = "Shows the keys a host offers, and records the one you confirm.")
public class CredentialsTrustHostCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "<host>[:<port>]",
            description =
                    "The host, as it appears in the address - 'github.com', or 'forge.example.org:7999'"
                            + " for one whose ssh is on a port other than 22.")
    private String host;

    @Option(
            names = "--fingerprint",
            paramLabel = "<SHA256:...>",
            description = "The key you confirmed. Without this, nothing is recorded.")
    private @Nullable String fingerprint;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        // On its own port, and from here on named as every hint names it: a key on 22 is another
        // service's.
        try {
            host = HostKeys.Address.of(host).name();
        } catch (final IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage() + ".");
            err.flush();
            return 64;
        }

        if (fingerprint == null) {
            final List<HostKeys.Offered> offered = HostKeys.offeredBy(context, host);
            if (offered.isEmpty()) {
                err.println(
                        "porelo: "
                                + host
                                + " offered nothing - it may be unreachable from"
                                + " here, or not be an ssh host at all.");
                err.flush();
                return 70;
            }
            out.println(host + " offers:");
            for (final HostKeys.Offered key : offered) {
                out.printf("    %-12s %s%n", key.type(), key.fingerprint());
            }
            out.println();
            out.println("Compare one with what you were told - not with what this screen says,");
            out.println("which is what somebody in the middle would also be showing you. Then:");
            out.println("    porelo credentials trust-host " + host + " --fingerprint <the one>");
            if (HostKeys.known(context, host)) {
                out.println();
                out.println("This machine already remembers a key for " + host + ".");
            }
            out.flush();
            return 0;
        }

        final HostKeys.Offered recorded;
        try {
            recorded = HostKeys.trust(context, host, fingerprint);
        } catch (final java.io.IOException ex) {
            err.println("porelo: cannot write what this machine remembers: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (recorded == null) {
            err.println("porelo: " + host + " offers no key with that fingerprint right now.");
            err.println(
                    "       Nothing was recorded. Look again with 'porelo credentials"
                            + " trust-host "
                            + host
                            + "'.");
            err.flush();
            return 70;
        }
        out.println("recorded  " + recorded.type() + "  " + recorded.fingerprint());
        out.println("          " + host + " is known to this machine from now on");
        out.flush();
        return 0;
    }
}
