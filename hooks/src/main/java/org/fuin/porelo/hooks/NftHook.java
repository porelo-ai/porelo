package org.fuin.porelo.hooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.SystemProgram;

/**
 * Loads the generated nftables ruleset into the container's network namespace.
 *
 * <p><strong>Fail-closed.</strong> If anything here goes wrong the container must not start: a task
 * container that comes up without its egress policy is exactly the situation Porelo exists to
 * prevent, and it would look completely normal to the operator.
 *
 * <p>Runs at {@code createRuntime}, where the runtime has created the namespaces but has not yet
 * pivoted into the container's root filesystem. The ruleset was generated on the host before the
 * container was created, so this hook makes no policy decision of its own.
 */
public class NftHook extends Hook {

    /**
     * How long loading the ruleset may take: below the 30 seconds the runtime gives the hook, so
     * this limit is the one that holds and the hook log says why the container was refused.
     */
    static final Duration LIMIT = Duration.ofSeconds(20);

    private final LongFunction<List<String>> program;

    private final Duration limit;

    /** Constructor. */
    public NftHook() {
        // nsenter is used rather than a Java thread entering the namespace: setns() is per-thread
        // and the JVM offers no control over which thread anything runs on. A child process is the
        // only way to be sure the ruleset lands in the container's namespace and not the host's.
        //
        // The ruleset goes in on stdin rather than as a path. Under SELinux, running nft
        // transitions into a confined domain that cannot open the operator's runtime files, and the
        // denial is dontaudit'ed - so a path fails as "Permission denied" with nothing in the audit
        // log.
        this(NftHook::command, LIMIT);
    }

    static List<String> command(long pid, Function<String, Path> lookup) {
        return List.of(
                lookup.apply("nsenter").toString(),
                "--target",
                String.valueOf(pid),
                "--net",
                lookup.apply("nft").toString(),
                "--file",
                "-");
    }

    private static List<String> command(long pid) {
        return command(pid, SystemProgram::find);
    }

    /**
     * Constructor for tests.
     *
     * @param program The command that loads the ruleset read on its stdin into the namespace of the
     *     given process.
     * @param limit How long it may take.
     */
    NftHook(LongFunction<List<String>> program, Duration limit) {
        super("nft", true);
        this.program = program;
        this.limit = limit;
    }

    @Override
    protected void run(String stage, OciState state, Sidecar sidecar) throws Exception {

        if ("poststop".equals(stage)) {
            // At poststop the namespace is already gone; there is nothing to unload.
            return;
        }
        if (!"createRuntime".equals(stage)) {
            // Answered as a success, any other stage - or none - started the container with no
            // ruleset.
            throw new IOException(
                    "The ruleset is loaded at createRuntime, and this hook was run at '"
                            + stage
                            + "'; the container is refused rather than started without its firewall");
        }

        final Path ruleset = Path.of(sidecar.rulesetFile());
        if (!Files.isRegularFile(ruleset)) {
            throw new IOException("No ruleset at " + ruleset);
        }
        if (state.pid() <= 0) {
            throw new IOException("The runtime reported no container process");
        }

        final byte[] rules = Files.readAllBytes(ruleset);
        final Process process =
                new ProcessBuilder(program.apply(state.pid())).redirectErrorStream(true).start();

        // Written and read beside the wait, not before it: reading the output first waited until
        // nft closed it, so a hung nft was never timed out here and the runtime killed the hook
        // with no line in its log.
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Thread writer =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try (OutputStream in = process.getOutputStream()) {
                                        in.write(rules);
                                    } catch (IOException ex) {
                                        // An nft that exits early closes its stdin; its exit code
                                        // says why.
                                    }
                                });
        final Thread reader =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try (InputStream out = process.getInputStream()) {
                                        out.transferTo(output);
                                    } catch (IOException ex) {
                                        // A destroyed process closes its output; what was read is
                                        // kept.
                                    }
                                });

        if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException(
                    "Loading the ruleset timed out after "
                            + limit.toSeconds()
                            + " s; the container is refused rather than started without its firewall");
        }
        writer.join(limit.toMillis());
        reader.join(limit.toMillis());
        final String said = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(
                    "nft failed with exit code "
                            + process.exitValue()
                            + (said.isBlank() ? "" : ": " + said.strip()));
        }
    }

    /**
     * Entry point of the {@code porelo-hook-nft} binary.
     *
     * @param args Command line arguments; the first is the stage name.
     */
    public static void main(String[] args) {
        System.exit(new NftHook().execute(args, System.in, System.err));
    }
}
