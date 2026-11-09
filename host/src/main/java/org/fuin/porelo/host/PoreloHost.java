package org.fuin.porelo.host;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

/** The host's own program, invoked by sudo rules or root's units. */
@Command(
        name = "porelo-host",
        mixinStandardHelpOptions = true,
        versionProvider = HostVersion.class,
        description = "The host's own program, run by sudo rules and root's units.",
        exitCodeOnInvalidInput = 64,
        exitCodeOnExecutionException = 69)
public final class PoreloHost implements Callable<Integer> {
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

    /** Checks the root boundary before handing any argument to picocli. */
    public static void main(String[] args) {
        // The caller's LANG and LC_* choose neither case folding nor the bytes written.
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        System.exit(
                run(
                        args,
                        System.getenv(),
                        HostUid::real,
                        new PrintWriter(
                                new java.io.OutputStreamWriter(
                                        System.out, java.nio.charset.StandardCharsets.UTF_8),
                                true),
                        new PrintWriter(
                                new java.io.OutputStreamWriter(
                                        System.err, java.nio.charset.StandardCharsets.UTF_8),
                                true)));
    }

    @FunctionalInterface
    interface Uid {
        long real() throws IOException;
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err) {
        try {
            HostEnvironment.check(environment);
            final boolean informational = args.length == 1 && answersAlone(args[0]);
            if (!informational && uid.real() != 0) {
                throw new HostRefusal(
                        77,
                        "porelo-host: root uid required; use the installed sudo rule or root's unit");
            }
            // picocli answers a version or help request even beside an argument it cannot match,
            // so "-Dx=y --version" would exit 0: before the verb they stand alone or not at all.
            if (args.length > 1
                    && java.util.Arrays.stream(args)
                            .takeWhile(argument -> argument.startsWith("-"))
                            .anyMatch(PoreloHost::answersAlone)) {
                throw new HostRefusal(
                        64, "porelo-host: --version and --help take no other argument");
            }
            return commandLine().setOut(out).setErr(err).execute(args);
        } catch (HostRefusal ex) {
            err.println(ex.getMessage());
            return ex.exitCode();
        } catch (IOException ex) {
            err.println("porelo-host: cannot establish the real uid; check procfs");
            return 77;
        }
    }

    private static boolean answersAlone(String argument) {
        return argument.equals("--version")
                || argument.equals("-V")
                || argument.equals("--help")
                || argument.equals("-h");
    }

    static CommandLine commandLine() {
        final CommandLine command = new CommandLine(new PoreloHost());
        command.setExpandAtFiles(false);
        command.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.OFF));
        command.getCommandSpec().usageMessage().autoWidth(false).width(80);
        return command;
    }

    /** Refuses an invocation without a verb; this frame has no operational verbs yet. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return 64;
    }
}
