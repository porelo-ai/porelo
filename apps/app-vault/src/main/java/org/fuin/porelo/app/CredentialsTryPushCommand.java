package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Tries one key without saving it or publishing work. */
@Command(
        name = "try-push",
        mixinStandardHelpOptions = true,
        description = "Tries one SSH key with git push --dry-run; saves nothing.")
public class CredentialsTryPushCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @Parameters(index = "0", description = "SSH repository address.")
    private String url;

    @Option(names = "--repository", required = true, description = "Local Git repository.")
    private Path repository;

    @Option(names = "--refspec", required = true, description = "source:refs/heads/branch to test.")
    private String refspec;

    @Option(names = "--agent", description = "Select from the SSH agent.")
    private boolean agent;

    @Option(names = "--key", description = "Agent fingerprint or public key.")
    private @Nullable String key;

    @Option(names = "--file", description = "Private key file.")
    private @Nullable String file;

    @Spec private CommandSpec spec;
    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        if (agent ? key == null || file != null : file == null || key != null) {
            spec.commandLine()
                    .getErr()
                    .println("porelo: say --agent --key <fingerprint> or --file <private-key>");
            return 64;
        }
        final var result =
                PushCredentialHelp.tryPush(
                        context,
                        repository,
                        url,
                        refspec,
                        agent,
                        java.util.Objects.requireNonNull(
                                agent ? key : file, "the selected source has a key"));
        spec.commandLine().getOut().println(result.get("message"));
        return Boolean.TRUE.equals(result.get("accepted")) ? 0 : 1;
    }
}
