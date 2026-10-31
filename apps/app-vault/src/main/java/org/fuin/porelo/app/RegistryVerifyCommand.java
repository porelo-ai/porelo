package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.RegistryVerifier;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Renders a host's verified registry state, or refuses without a partial answer. */
@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Checks host signatures, pinned person cards and ancestry in a local registry.")
public class RegistryVerifyCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @Option(
            names = "--repository",
            required = true,
            paramLabel = "BUNDLE",
            description = "A whole Git bundle in a regular file, not a symlink.")
    private Path repository = Path.of("");

    @Option(
            names = "--host",
            required = true,
            paramLabel = "HOST",
            description = "The host's lower-case DNS label.")
    private String host = "";

    @Option(
            names = "--pinned",
            required = true,
            paramLabel = "FINGERPRINT",
            description = "The trusted host admin's SHA256 fingerprint.")
    private String pinned = "";

    @Option(
            names = "--since",
            paramLabel = "COMMIT",
            description = "The previously applied commit; head must descend from it.")
    private @Nullable String since;

    @Option(names = "--json", description = "Answers the sync contract as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private CommandRunner runner = new ProcessCommandRunner();

    @Override
    public void setContext(PoreloContext context) {
        runner = context.runner();
    }

    @Override
    public Integer call() {
        final Map<String, Object> answer;
        try {
            if (since != null && since.isEmpty()) {
                return refuse(64, "--since must name a commit");
            }
            answer =
                    new RegistryVerifier(runner)
                            .verify(repository, host, pinned, since == null ? "" : since);
        } catch (IllegalArgumentException ex) {
            return refuse(64, ex.getMessage());
        } catch (IOException ex) {
            return refuse(66, ex.getMessage());
        } catch (CardException | CommandException ex) {
            return refuse(65, ex.getMessage());
        }
        final var out = spec.commandLine().getOut();
        if (json) {
            out.println(Json.write(printable(answer)));
        } else {
            final Map<?, ?> card =
                    (Map<?, ?>)
                            Objects.requireNonNull(
                                    answer.get("host_card"),
                                    "the verifier always answers the host card");
            final Map<?, ?> admins =
                    (Map<?, ?>)
                            Objects.requireNonNull(
                                    answer.get("admins"), "the verifier always answers admins");
            final java.util.List<?> projects =
                    (java.util.List<?>)
                            Objects.requireNonNull(
                                    answer.get("projects"), "the verifier always answers projects");
            out.println("Registry " + answer.get("commit"));
            out.println("Host " + host + ", verified against " + card.get("signed_by"));
            out.println("Admins: " + admins.size());
            out.println("Projects: " + projects.size());
        }
        out.flush();
        return 0;
    }

    private int refuse(int code, @Nullable String message) {
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + printable(String.valueOf(message)));
        err.flush();
        return code;
    }

    private static Object printable(Object value) {
        return switch (value) {
            case String text -> text.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
            case List<?> values ->
                    values.stream()
                            .map(
                                    item ->
                                            printable(
                                                    Objects.requireNonNull(
                                                            item,
                                                            "registry lists contain no null values")))
                            .toList();
            case Map<?, ?> values -> {
                final Map<String, Object> safe = new LinkedHashMap<>();
                for (var entry : values.entrySet()) {
                    safe.put(
                            printable(
                                            Objects.requireNonNull(
                                                    entry.getKey(),
                                                    "registry keys are nonnull text"))
                                    .toString(),
                            printable(
                                    Objects.requireNonNull(
                                            entry.getValue(),
                                            "registry fields contain no null values")));
                }
                yield safe;
            }
            default -> value;
        };
    }
}
