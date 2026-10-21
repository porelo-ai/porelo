package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.vault.VaultEntry;

/**
 * Which installed agents the vault holds a credential for, in a form a program reads.
 *
 * <p>A host's play stops for an agent's sign-in only where one is missing, so "signed in" follows
 * the rule a login stores by: under the provider the agent defaults to, or under its own name when
 * it is not brokered. It carries names and whether something is stored, never a value.
 */
public final class AgentSignIns {

    /** The vault opens with what this account keeps. */
    public static final String OPEN = "open";

    /** The vault is there and nothing this account keeps opens it. */
    public static final String SHUT = "shut";

    /** There is no vault. */
    public static final String NONE = "none";

    /**
     * One installed agent as the report needs it.
     *
     * @param definition What it describes itself as.
     * @param foundBy The description file that named it, or its executable when none did.
     */
    public record Found(AgentDefinition definition, Path foundBy) {}

    private AgentSignIns() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reports each agent with the vault entry it signs in under and whether the vault holds it.
     *
     * <p>{@code signed_in} is given only while the vault is open: shut, whether anything is stored
     * is unknown, and with no vault the play has a vault to make before any sign-in. {@code
     * credential} and {@code signed_in} are both left out for an agent that names no default
     * provider, since a start would have to choose one first.
     *
     * @param agents The installed agents, in the order they are listed.
     * @param providers The providers this machine declares.
     * @param vaultExists Whether there is a vault at all.
     * @param readable The vault's entries, or empty when it could not be read.
     * @return The report as plain values: {@code vault} and {@code agents}.
     */
    public static Map<String, Object> report(
            List<Found> agents,
            Map<String, ProviderDefinition> providers,
            boolean vaultExists,
            Optional<Map<String, VaultEntry>> readable) {
        final String vault = !vaultExists ? NONE : readable.isPresent() ? OPEN : SHUT;
        final java.util.Set<String> stored = readable.orElseGet(Map::of).keySet();
        final List<Map<String, Object>> rows =
                agents.stream()
                        .map(found -> row(found, providers, stored, vault.equals(OPEN)))
                        .toList();
        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("vault", vault);
        report.put("agents", rows);
        return report;
    }

    private static Map<String, Object> row(
            Found found,
            Map<String, ProviderDefinition> providers,
            java.util.Set<String> stored,
            boolean open) {
        final AgentDefinition agent = found.definition();
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", agent.name());
        row.put("found_by", found.foundBy().toString());
        final SelectedProvider.Choice choice = SelectedProvider.consider(providers, agent, null);
        if (choice.refusal() == SelectedProvider.Refusal.NO_PROVIDER_CHOSEN) {
            return row;
        }
        final String credential =
                SelectedProvider.credentialKey(stored, agent.name(), choice.wanted());
        row.put("credential", credential);
        if (open) {
            row.put("signed_in", stored.contains(credential));
        }
        return row;
    }
}
