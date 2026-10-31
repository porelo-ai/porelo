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
 * <p>A project's task is certified by one of its certifiers, the admitted persons of the verified
 * access commit, through the starter's own agent when it holds one of their keys, or by the
 * starting person's machine when the start came from there. A task with no project is certified by
 * the starting person alone: from their machine, or by the first Ed25519 key their agent holds when
 * they start it here. Otherwise the task starts uncertified: its commits are not signed and its
 * gate judges nothing (decided 2026-10-31 for starts without a card key; michi confirms).
 */
final class TaskCertifying {

    private TaskCertifying() {}

    /**
     * Returns the keys allowed to certify a project's tasks.
     *
     * @param context The machine.
     * @param project The project.
     * @return Their keys, empty for a project without a verified access commit.
     */
    static List<String> certifiers(PoreloContext context, Project project) {
        if (project.access() == null) {
            return List.of();
        }
        try {
            return ProjectAccess.persons(context, project).stream()
                    .filter(org.fuin.porelo.vault.AccessPersons.Person::admitted)
                    .map(org.fuin.porelo.vault.AccessPersons.Person::ssh)
                    .filter(key -> !key.isBlank())
                    .toList();
        } catch (ProjectAccess.Withheld ex) {
            return List.of();
        }
    }

    /**
     * Returns the keys that may certify a task anew.
     *
     * <p>A task without a project is certified again by the key that certified it at its start,
     * since its gate checks that one, whichever key the agent now holds first. A project's task is
     * certified by its access file's certifiers, and by no key when there are none.
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
