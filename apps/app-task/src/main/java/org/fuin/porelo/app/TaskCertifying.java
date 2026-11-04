package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.project.Project;
import org.jspecify.annotations.Nullable;

/**
 * Decides at a task's start whether and by whom its key is certified.
 *
 * <p>A project's task is certified by one of its certifiers, its members with a current key,
 * through the starter's own agent when it holds one of their keys, or by the starting person's
 * machine when the start came from there. A task with no project is certified by the starting
 * person alone: from their machine, or by the first Ed25519 key their agent holds when they start
 * it here. Otherwise the task starts uncertified: its commits are not signed and its gate judges
 * nothing.
 */
final class TaskCertifying {

    private TaskCertifying() {}

    /**
     * Returns the keys allowed to certify a project's tasks.
     *
     * @param context The machine.
     * @param project The project.
     * @return Their keys, empty for a project of this machine alone.
     */
    static List<String> certifiers(PoreloContext context, Project project) {
        return ProjectMembers.certifiers(context, project.name());
    }

    /**
     * Returns the keys that may certify a task anew.
     *
     * <p>A task without a project is certified again by the key that certified it at its start,
     * since its gate checks that one, whichever key the agent now holds first. A project's task is
     * certified by its members' keys, and by no key when there are none.
     *
     * @param project The task's project, or {@link TaskCertification#NO_PROJECT}.
     * @param certificate The task's certificate as it holds it.
     * @param certifiers The project's certifiers' keys.
     * @return The keys, empty when no key may.
     */
    static List<String> renewing(String project, String certificate, List<String> certifiers) {
        if (TaskCertification.NO_PROJECT.equals(project)) {
            return List.of(org.fuin.porelo.vault.SshCertificate.read(certificate).signer());
        }
        return certifiers;
    }

    /**
     * Says why a host starts no task uncertified, and what helps. A person here over ssh or through
     * an interface holds their key on their own computer, which a host does not forward to: so that
     * way comes first, whole but for the address.
     *
     * @param project The project's name.
     * @param task The task's name.
     * @param login Who starts it.
     * @param certifiers The project's certifiers' keys.
     * @param repository The repository it is for.
     * @return The words.
     */
    static String noCertificate(
            String project, String task, String login, List<String> certifiers, String repository) {
        final String refused =
                "this host starts a task only with a key of its own that you certify";
        if (certifiers.isEmpty() && !TaskCertification.NO_PROJECT.equals(project)) {
            return refused
                    + ", and project "
                    + project
                    + "'s access file names no key that may certify it";
        }
        return refused
                + ". On your own computer, where your agent holds the key of your card, run:"
                + " porelo task start "
                + task
                + " --host "
                + login
                + "@<this machine's address> --project "
                + project
                + " --repository "
                + repository
                + " (an agent on this machine holding that key does as well: ssh-add it and start"
                + " again)";
    }

    /**
     * Certifies a task at its start, or answers that it starts uncertified.
     *
     * @param project The project's name, or {@link TaskCertification#NO_PROJECT}.
     * @param task The task's name.
     * @param login Who starts it.
     * @param certifiers The project's certifiers' keys.
     * @param remote The starting person's machine, or {@code null} for a start on this one.
     * @param agent This machine's agent socket, or {@code null}.
     * @param clock The time.
     * @return The certified key, or {@code null} for an uncertified task.
     * @throws TaskCertification.Refused If a certificate came back that is not accepted.
     */
    static TaskCertification.@Nullable Issued atStart(
            String project,
            String task,
            String login,
            List<String> certifiers,
            TaskCertification.@Nullable Certifier remote,
            @Nullable Path agent,
            Clock clock) {
        // A project naming no certifier starts its tasks uncertified, wherever they are asked for.
        if (certifiers.isEmpty() && !TaskCertification.NO_PROJECT.equals(project)) {
            return null;
        }
        if (remote != null) {
            return TaskCertification.certify(project, task, login, remote, certifiers, clock);
        }
        if (agent == null) {
            return null;
        }
        final String held =
                TaskCertification.NO_PROJECT.equals(project)
                        ? agentHolds(agent, null)
                        : certifiers.isEmpty() ? null : agentHolds(agent, certifiers);
        if (held == null) {
            return null;
        }
        return TaskCertification.certify(
                project, task, login, new AgentCertifier(held, agent), certifiers, clock);
    }

    /**
     * The first of the keys that the agent holds, or with {@code null} its first Ed25519 key;
     * {@code null} when it holds none.
     */
    static @Nullable String agentHolds(Path agent, @Nullable List<String> keys) {
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder("ssh-add", "-L").redirectErrorStream(false);
            builder.environment().put("SSH_AUTH_SOCK", agent.toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            final Process process = builder.start();
            final String listed =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            for (final String line : listed.lines().toList()) {
                final String[] fields = line.strip().split(" ");
                if (fields.length >= 2
                        && (keys == null
                                ? fields[0].equals("ssh-ed25519")
                                : keys.contains(fields[0] + " " + fields[1]))) {
                    return fields[0] + " " + fields[1];
                }
            }
            return null;
        } catch (IOException ex) {
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
