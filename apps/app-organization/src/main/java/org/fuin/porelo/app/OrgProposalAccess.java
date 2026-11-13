package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.ForgeAddress;

/** Lends the organization's declared token to its same-forge proposal branch and fork. */
final class OrgProposalAccess {
    private OrgProposalAccess() {}

    static FetchAccess lent(PoreloContext machine, ForgeAddress organization) {
        final FetchAccess usual = OrganizationAccess.lent(machine);
        return target -> {
            if (!organization.url().startsWith("https://") || !target.startsWith("https://")) {
                return usual.lend(target);
            }
            final ForgeAddress destination = ForgeAddress.of(target);
            final Credential declared =
                    machine.credentialRegistry().forUrl("git", organization.url());
            if (!destination.forge().equals(organization.forge())
                    || !destination.repository().equals(organization.repository())
                    || machine.credentialRegistry().forUrl("git", target) != null
                    || declared == null
                    || declared.kind() != Credential.Kind.TOKEN) {
                return usual.lend(target);
            }
            final FollowCredential credential =
                    FollowCredential.open(machine, target, "organization", "git", declared);
            return new FetchAccess.Lease() {
                @Override
                public Map<String, String> environment() {
                    final Map<String, String> values =
                            new LinkedHashMap<>(credential.environment());
                    final String socket = machine.environment().apply(OrganizationAccess.AGENT);
                    // Never a declared credential's value taken for the agent.
                    if (socket != null
                            && !socket.isEmpty()
                            && !credential.secretsLent().contains(socket))
                        values.putIfAbsent(OrganizationAccess.AGENT, socket);
                    return Map.copyOf(values);
                }

                @Override
                public List<String> arguments() {
                    return credential.arguments();
                }

                @Override
                public java.util.Set<String> lent() {
                    return credential.secretsLent();
                }

                @Override
                public void close() {
                    credential.close();
                }
            };
        };
    }
}
