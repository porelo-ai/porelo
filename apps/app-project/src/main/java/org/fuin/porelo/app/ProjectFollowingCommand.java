package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Says which projects this account takes its configuration from, and how that is going.
 *
 * <p>The state is per account and not per machine: two people here follow different projects and
 * reconcile at different moments, because each reaches their own vault.
 */
@Command(
        name = "following",
        mixinStandardHelpOptions = true,
        description = "Lists the project repositories this account follows.")
public class ProjectFollowingCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @picocli.CommandLine.Option(
            names = "--json",
            description =
                    "Prints the follows as one JSON object, for a program: name, url, commit,"
                            + " outcome, verified and the keys each is signed by.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final var followed = new FollowedProjects(context.paths().projects().followed()).all();
        if (json) {
            return json(out, followed);
        }
        if (followed.isEmpty()) {
            out.println("this account follows no project repository");
            out.println("start with: porelo projects follow <name> <url>");
            out.flush();
            return 0;
        }
        out.printf("%-20s %-12s %-10s %s%n", "PROJECT", "COMMIT", "LAST", "WHERE");
        final java.util.Map<String, java.util.List<String>> signers =
                PinnedSigners.of(context.paths().projects().configurationSigners());
        for (final var one : followed) {
            out.printf(
                    "%-20s %-12s %-10s %s%n",
                    one.name(),
                    one.commit().isEmpty() ? "-" : one.commit().substring(0, 12),
                    one.outcome().isEmpty()
                            ? "never"
                            : one.outcome().toLowerCase(java.util.Locale.ROOT),
                    one.url());
            if (one.unverified()) {
                // Said on its own line rather than squeezed into a column: it is the one thing here
                // that changes who decides what tasks on this machine may reach.
                out.println(
                        "    unverified - whoever can push there decides what tasks here may"
                                + " reach");
            }
            for (final String fingerprint : signers.getOrDefault(one.name(), java.util.List.of())) {
                out.println("    signed by  " + fingerprint);
            }
            if (!one.detail().isEmpty()) {
                out.println("    " + one.detail());
            }
            if (!one.refused().isEmpty()) {
                out.println(
                        "    refused    "
                                + one.refused()
                                + (one.signer().isEmpty() ? "" : "  signed by " + one.signer()));
            }
            if (!one.accessOutcome().isEmpty()) {
                // Its own line: no transport runs for the project until this commit is set.
                out.println(
                        "    access     "
                                + (one.accessCommit().isEmpty()
                                        ? "none in force"
                                        : one.accessCommit()
                                                .substring(
                                                        0,
                                                        Math.min(12, one.accessCommit().length())))
                                + "  "
                                + one.accessOutcome().toLowerCase(java.util.Locale.ROOT));
            }
        }
        out.flush();
        return 0;
    }

    /**
     * Prints the follows for a program, so a playbook follows a project only where one of these
     * differs. The names are kept: a program reads them, and a rename fails its test, not a play.
     *
     * @param out Where to print.
     * @param followed The follows, as {@link FollowedProjects} has them.
     * @return Zero.
     */
    private int json(
            final PrintWriter out, final java.util.List<FollowedProjects.Followed> followed) {
        final java.util.Map<String, java.util.List<String>> signers =
                PinnedSigners.of(context.paths().projects().configurationSigners());
        final java.util.List<Object> follows = new java.util.ArrayList<>();
        for (final var one : followed) {
            final java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", one.name());
            entry.put("url", one.url());
            entry.put("commit", one.commit());
            entry.put("outcome", one.outcome().toLowerCase(java.util.Locale.ROOT));
            entry.put("verified", !one.unverified());
            entry.put("signed_by", signers.getOrDefault(one.name(), java.util.List.of()));
            entry.put("detail", one.detail());
            entry.put("refused", one.refused());
            entry.put("access_commit", one.accessCommit());
            entry.put("access_outcome", one.accessOutcome().toLowerCase(java.util.Locale.ROOT));
            follows.add(entry);
        }
        out.println(org.fuin.porelo.wire.Json.write(java.util.Map.of("follows", follows)));
        out.flush();
        return 0;
    }
}
