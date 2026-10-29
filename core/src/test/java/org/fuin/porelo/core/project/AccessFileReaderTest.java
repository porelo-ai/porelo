package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards against applying ambiguous access settings, conflicting VPN addresses, or private keys.
 */
class AccessFileReaderTest {

    private static final String ORIGIN = "checkout/access.yml";
    private static final String WIREGUARD = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String CA = "0123456789abcdef".repeat(4);
    private static final String HOST =
            """
            host:
              address: host.example.org
              endpoint: host.example.org:51820
              wireguard_public_key: %s
              network: 10.77.0.0/24
              ssh_host_key: ssh-ed25519 AAAA host@example.org
              ca_sha256: %s
            """
                    .formatted(WIREGUARD, CA);
    private static final String DEVELOPERS =
            """
            developers:
              alice:
                laptops:
                  - ssh_public_key: ssh-ed25519 AAAA alice@laptop
                    wireguard_public_key: %s
                    vpn_address: 10.77.0.10
                forge_login: alice-gh
            """
                    .formatted(WIREGUARD);

    private static AccessFile read(final String yaml) {
        return AccessFile.read(new StringReader(yaml), ORIGIN);
    }

    private static void refuses(final String yaml, final String path, final String reason) {
        assertThatThrownBy(() -> read(yaml))
                .as("invalid access settings must be refused at %s", path)
                .isInstanceOf(ProjectException.class)
                .hasMessageStartingWith(ORIGIN + ": ")
                .hasMessageContaining("'" + path + "'")
                .hasMessageContaining(reason);
    }

    @Test
    void an_empty_mapping_names_nothing() {
        assertThat(read("{}"))
                .as("all three sections are optional")
                .isEqualTo(new AccessFile(null, Map.of(), Map.of()));
    }

    @Test
    void a_complete_file_keeps_the_public_settings_and_drops_ssh_comments() {
        final AccessFile access = read(HOST + DEVELOPERS);

        assertThat(access.host())
                .as("the host fields reach the prepared host unchanged except for the SSH comment")
                .isEqualTo(
                        new AccessFile.Host(
                                "host.example.org",
                                "host.example.org:51820",
                                WIREGUARD,
                                "10.77.0.0/24",
                                "ssh-ed25519 AAAA",
                                CA));
        assertThat(access.developers())
                .as("the developer's public keys and assigned address stay together")
                .containsExactly(
                        Map.entry(
                                "alice",
                                new AccessFile.Developer(
                                        "alice",
                                        List.of(
                                                new AccessFile.Laptop(
                                                        "ssh-ed25519 AAAA",
                                                        WIREGUARD,
                                                        "10.77.0.10")),
                                        "alice-gh")));
    }

    @Test
    void a_host_can_be_named_without_developers_or_transports() {
        final AccessFile access = read(HOST);
        assertThat(access.host()).as("a prepared host does not require laptops yet").isNotNull();
        assertThat(access.developers()).as("no developer is invented").isEmpty();
        assertThat(access.transports()).as("no transport is invented").isEmpty();
    }

    @Test
    void empty_developers_and_transports_do_not_require_a_host() {
        assertThat(read("developers: {}\ntransports: {}"))
                .as("empty optional sections name nothing")
                .isEqualTo(new AccessFile(null, Map.of(), Map.of()));
    }

    @Test
    void developers_and_laptops_keep_their_file_order_and_forge_login_is_optional() {
        final String developers =
                DEVELOPERS.replace("    forge_login: alice-gh\n", "")
                        + DEVELOPERS
                                .substring("developers:\n".length())
                                .replace("alice", "bob")
                                .replace("10.77.0.10", "10.77.0.11");
        final AccessFile access = read(HOST + developers);
        assertThat(access.developers().keySet())
                .as("developer order is file order")
                .containsExactly("alice", "bob");
        assertThat(access.developers().get("alice").forgeLogin())
                .as("forge login may be absent")
                .isNull();
        final String twoLaptops =
                DEVELOPERS.replace(
                        "    forge_login: alice-gh",
                        "      - ssh_public_key: ssh-rsa BBBB second@laptop\n"
                                + "        wireguard_public_key: "
                                + WIREGUARD
                                + "\n"
                                + "        vpn_address: 10.77.0.11");
        assertThat(read(HOST + twoLaptops).developers().get("alice").laptops())
                .as("laptop order is file order")
                .extracting(AccessFile.Laptop::vpnAddress)
                .containsExactly("10.77.0.10", "10.77.0.11");
    }

    @Test
    void transport_settings_keep_their_values_types_and_file_order_without_a_host() {
        final AccessFile access =
                read(
                        """
                transports:
                  matrix:
                    room: '#p:example.org'
                    homeserver: https://chat.example.org
                    options: {enabled: true, retries: 3, extra: [one, null]}
                  mail-2: {}
                """);
        assertThat(access.transports().keySet())
                .as("scheme order is file order")
                .containsExactly("matrix", "mail-2");
        final Map<?, ?> settings = (Map<?, ?>) access.transports().get("matrix");
        assertThat(settings.keySet().toArray())
                .as("setting order is file order")
                .containsExactly("room", "homeserver", "options");
        assertThat(settings.get("options"))
                .as("transport-specific settings are not flattened or converted to strings")
                .isEqualTo(
                        Map.of(
                                "enabled",
                                true,
                                "retries",
                                3,
                                "extra",
                                java.util.Arrays.asList("one", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# only a comment", "null", "[]", "plain text", "42"})
    void an_empty_document_or_a_non_mapping_is_refused(final String yaml) {
        refuses(yaml, "$", "is empty or is not a YAML mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host: [", "{}\n---\n{}"})
    void malformed_yaml_or_more_than_one_document_is_refused(final String yaml) {
        refuses(yaml, "$", "cannot parse YAML");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "host: {}\nhost: {}",
                "developers: {alice: {}, alice: {}}",
                "transports: {matrix: {room: one, room: two}}"
            })
    void duplicate_keys_are_refused_at_every_depth(final String yaml) {
        refuses(yaml, "$", "without duplicate keys");
    }

    @Test
    void yaml_cannot_construct_a_class_named_by_the_file() {
        refuses("transports: {matrix: {value: !!java.lang.String public}}", "$", "custom tags");
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra", "Host", "42", "[one, two]"})
    void an_unknown_top_level_key_is_refused(final String key) {
        refuses(key + ": {}", key.equals("[one, two]") ? "<non-text key>" : key, "is not known");
    }

    @Test
    void signers_are_the_project_files_alone() {
        refuses("signers: [ssh-ed25519 AAAA]", "signers", "belongs to the project file alone");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "address",
                "endpoint",
                "wireguard_public_key",
                "network",
                "ssh_host_key",
                "ca_sha256"
            })
    void every_host_key_is_required(final String key) {
        refuses(HOST.replaceAll("(?m)^  " + key + ":.*\\n", ""), "host." + key, "is required");
    }

    @Test
    void an_unknown_host_key_is_refused() {
        refuses(HOST + "  extra: value\n", "host.extra", "is not known");
    }

    @Test
    void an_unknown_developer_key_is_refused() {
        refuses(HOST + DEVELOPERS + "    extra: value\n", "developers.alice.extra", "is not known");
    }

    @Test
    void an_unknown_laptop_key_is_refused() {
        refuses(
                HOST + DEVELOPERS.replace("    forge_login: alice-gh", "        extra: value"),
                "developers.alice.laptops[0].extra",
                "is not known");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "text"})
    void a_host_section_that_is_not_a_mapping_is_refused(final String value) {
        refuses("host: " + value, "host", "must be a mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "text"})
    void developers_that_are_not_a_mapping_are_refused(final String value) {
        refuses("developers: " + value, "developers", "must be a mapping");
    }

    @Test
    void a_developer_that_is_not_a_mapping_is_refused() {
        refuses("developers: {alice: []}", "developers.alice", "must be a mapping");
    }

    @Test
    void a_laptop_that_is_not_a_mapping_is_refused() {
        refuses(
                HOST + "developers: {alice: {laptops: [text]}}",
                "developers.alice.laptops[0]",
                "must be a mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ssh_public_key", "wireguard_public_key", "vpn_address"})
    void every_laptop_key_is_required(final String key) {
        final String yaml =
                key.equals("ssh_public_key")
                        ? DEVELOPERS
                                .replace("ssh_public_key: ssh-ed25519 AAAA alice@laptop", "{}")
                                .replace("      - {}\n", "      -\n")
                        : DEVELOPERS.replaceAll("(?m)^        " + key + ":.*\\n", "");
        refuses(HOST + yaml, "developers.alice.laptops[0]." + key, "is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "''", "42", "true", "[]", "{}"})
    void a_required_field_must_be_nonempty_text(final String value) {
        refuses(
                HOST.replace("address: host.example.org", "address: " + value),
                "host.address",
                "is required");
    }

    @Test
    void a_present_forge_login_must_be_nonempty_text() {
        refuses(
                HOST + DEVELOPERS.replace("forge_login: alice-gh", "forge_login: []"),
                "developers.alice.forge_login",
                "is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "{}", "text"})
    void laptops_must_be_a_nonempty_list(final String value) {
        refuses(
                HOST + "developers: {alice: {laptops: " + value + "}}",
                "developers.alice.laptops",
                "at least one laptop");
    }

    @Test
    void a_missing_laptop_list_is_refused() {
        refuses(
                HOST + "developers: {alice: {}}",
                "developers.alice.laptops",
                "at least one laptop");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Alice",
                "1alice",
                "-alice",
                "alice.name",
                "alice/other",
                "abcdefghijklmnopqrstuvwxyzabcdefg",
                "42"
            })
    void an_invalid_account_name_is_refused(final String account) {
        refuses(
                HOST + DEVELOPERS.replace("  alice:", "  " + account + ":"),
                "developers." + account,
                "is not an account name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"_", "a-b_0", "abcdefghijklmnopqrstuvwxyzabcdef"})
    void account_names_include_the_documented_boundaries(final String account) {
        assertThat(read(HOST + DEVELOPERS.replace("  alice:", "  " + account + ":")).developers())
                .as("valid accounts are not refused by an overly narrow pattern")
                .containsKey(account);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "short=",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!=",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB="
            })
    void a_malformed_host_wireguard_key_is_refused(final String value) {
        refuses(
                HOST.replace(WIREGUARD, value),
                "host.wireguard_public_key",
                "WireGuard public key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void a_malformed_laptop_wireguard_key_is_refused(final String value) {
        refuses(
                HOST + DEVELOPERS.replace(WIREGUARD, value),
                "developers.alice.laptops[0].wireguard_public_key",
                "WireGuard public key");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh-dss AAAA",
                "ssh-ed25519",
                "ssh-ed25519 %%%%",
                "AAAA",
                "prefix ssh-ed25519 AAAA"
            })
    void a_malformed_host_ssh_key_is_refused(final String value) {
        refuses(
                HOST.replace("ssh-ed25519 AAAA host@example.org", value),
                "host.ssh_host_key",
                "is not a public key");
    }

    @Test
    void a_malformed_laptop_ssh_key_is_refused() {
        refuses(
                HOST + DEVELOPERS.replace("ssh-ed25519 AAAA alice@laptop", "ssh-dss AAAA"),
                "developers.alice.laptops[0].ssh_public_key",
                "is not a public key");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh-ed25519",
                "ssh-rsa",
                "ecdsa-sha2-nistp256",
                "ecdsa-sha2-nistp384",
                "ecdsa-sha2-nistp521",
                "sk-ssh-ed25519@openssh.com"
            })
    void ssh_key_types_match_the_project_signers_types(final String type) {
        final String publicKey = type + " AAAA";
        assertThat(
                        read(HOST.replace(
                                        "ssh-ed25519 AAAA host@example.org",
                                        publicKey + " comment"))
                                .host()
                                .sshHostKey())
                .as("the same public key types can identify the host and sign the project")
                .isEqualTo(publicKey);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "short",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeF",
                "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            })
    void a_malformed_ca_digest_is_refused(final String value) {
        refuses(HOST.replace(CA, value), "host.ca_sha256", "64 lower-case hex");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://host.example.org",
                "host:0",
                "host:65536",
                "host:-1",
                "host:abc",
                "host:",
                "host",
                "bad host:123",
                "999.1.2.3:123",
                "2001:db8::1:51820",
                "[bad]:123",
                "host:99999999999999999999999"
            })
    void a_malformed_endpoint_is_refused(final String value) {
        refuses(
                HOST.replace("host.example.org:51820", "'" + value + "'"),
                "host.endpoint",
                "port in 1..65535");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host.example.org:1", "192.0.2.1:65535", "[2001:db8::1]:51820"})
    void endpoint_hosts_and_port_boundaries_are_literal_settings(final String value) {
        assertThat(
                        read(HOST.replace("host.example.org:51820", "'" + value + "'"))
                                .host()
                                .endpoint())
                .as("endpoints need no DNS lookup and include bracketed IPv6")
                .isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.org",
                "bad host",
                "-host.example.org",
                "host..example.org",
                "999.1.2.3",
                "127.1",
                "2001:bad::bad::1",
                "host/path"
            })
    void a_malformed_host_address_is_refused(final String value) {
        refuses(
                HOST.replace("address: host.example.org", "address: '" + value + "'"),
                "host.address",
                "DNS name or IP address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1", "2001:db8::1", "::ffff:192.0.2.1", "host.example.org."})
    void host_addresses_include_ip_literals_and_absolute_dns_names(final String value) {
        assertThat(
                        read(HOST.replace("address: host.example.org", "address: " + value))
                                .host()
                                .address())
                .as("the host address is syntax checked without resolving it")
                .isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "10.77.0.0",
                "10.0.0.0/7",
                "10.77.0.0/31",
                "10.77.0.0/32",
                "10.77.0.0/abc",
                "10.77.0.1/24",
                "999.77.0.0/24",
                "2001:db8::/24",
                "host.example.org/24",
                "10.77.0.0/9999999999",
                "10.77.0.0/24/1"
            })
    void a_malformed_network_is_refused(final String value) {
        refuses(
                HOST.replace("10.77.0.0/24", value),
                "host.network",
                "IPv4 network in CIDR notation with prefix 8..30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/8", "10.77.0.0/30", "192.0.2.128/25", "255.255.255.252/30"})
    void network_prefix_boundaries_and_high_unsigned_addresses_are_supported(final String value) {
        assertThat(read(HOST.replace("10.77.0.0/24", value)).host().network())
                .as("IPv4 network arithmetic covers the full unsigned address space")
                .isEqualTo(value);
    }

    @Test
    void a_vpn_address_without_a_host_is_refused() {
        refuses(
                DEVELOPERS,
                "developers.alice.laptops[0].vpn_address",
                "there is no 'host' section");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "host.example.org",
                "10.77.0.256",
                "10.77.10",
                "10.77.0.-1",
                "010.77.0.10",
                "::1"
            })
    void a_vpn_address_that_is_not_dotted_ipv4_is_refused(final String value) {
        refuses(
                HOST + DEVELOPERS.replace("10.77.0.10", value),
                "developers.alice.laptops[0].vpn_address",
                "must be an IPv4 address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.76.255.255", "10.77.1.0"})
    void a_vpn_address_outside_the_network_is_refused(final String value) {
        refuses(
                HOST + DEVELOPERS.replace("10.77.0.10", value),
                "developers.alice.laptops[0].vpn_address",
                "is outside 'host.network'");
    }

    @Test
    void the_network_address_cannot_belong_to_a_laptop() {
        refuses(
                HOST + DEVELOPERS.replace("10.77.0.10", "10.77.0.0"),
                "developers.alice.laptops[0].vpn_address",
                "is the network address");
    }

    @Test
    void the_broadcast_address_cannot_belong_to_a_laptop() {
        refuses(
                HOST + DEVELOPERS.replace("10.77.0.10", "10.77.0.255"),
                "developers.alice.laptops[0].vpn_address",
                "is the broadcast address");
    }

    @Test
    void the_hosts_own_address_cannot_belong_to_a_laptop() {
        refuses(
                HOST + DEVELOPERS.replace("10.77.0.10", "10.77.0.1"),
                "developers.alice.laptops[0].vpn_address",
                "is the host's own address");
    }

    @Test
    void the_host_address_is_relative_to_the_network_base() {
        refuses(
                HOST.replace("10.77.0.0/24", "192.0.2.128/25")
                        + DEVELOPERS.replace("10.77.0.10", "192.0.2.129"),
                "developers.alice.laptops[0].vpn_address",
                "is the host's own address");
    }

    @Test
    void two_laptops_of_one_developer_cannot_share_a_vpn_address() {
        final String laptop =
                "      - ssh_public_key: ssh-rsa BBBB\n        wireguard_public_key: "
                        + WIREGUARD
                        + "\n        vpn_address: 10.77.0.10\n";
        refuses(
                HOST + DEVELOPERS.replace("    forge_login: alice-gh\n", laptop),
                "developers.alice.laptops[1].vpn_address",
                "already used by another laptop");
    }

    @Test
    void laptops_of_different_developers_cannot_share_a_vpn_address() {
        refuses(
                HOST
                        + DEVELOPERS
                        + DEVELOPERS.substring("developers:\n".length()).replace("alice", "bob"),
                "developers.bob.laptops[0].vpn_address",
                "already used by another laptop");
    }

    @Test
    void the_only_laptop_address_in_a_small_subnet_is_accepted() {
        assertThat(
                        read(HOST.replace("10.77.0.0/24", "255.255.255.252/30")
                                        + DEVELOPERS.replace("10.77.0.10", "255.255.255.254"))
                                .developers()
                                .get("alice")
                                .laptops()
                                .getFirst()
                                .vpnAddress())
                .as("a /30 leaves one laptop address after reserving the host")
                .isEqualTo("255.255.255.254");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "text"})
    void transports_must_be_a_mapping(final String value) {
        refuses("transports: " + value, "transports", "must be a mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "text"})
    void each_transports_settings_must_be_a_mapping(final String value) {
        refuses("transports: {matrix: " + value + "}", "transports.matrix", "must be a mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Matrix", "../matrix", "-matrix", "matrix_name", "matrix:room"})
    void an_invalid_transport_scheme_is_refused(final String scheme) {
        refuses(
                "transports: {'" + scheme + "': {}}",
                "transports." + scheme,
                "is not a transport's name");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "host:\n  address: '-----BEGIN PRIVATE KEY-----'",
                "developers: {alice: {forge_login: 'PRIVATE KEY'}}",
                "transports: {matrix: {nested: [{key: 'prefix PRIVATE KEY suffix'}]}}",
                "extra: 'PRIVATE KEY'"
            })
    void private_key_markers_are_refused_in_every_section_before_mapping(final String yaml) {
        assertThatThrownBy(() -> read(yaml))
                .as("private material cannot hide in an optional or transport-specific setting")
                .isInstanceOf(ProjectException.class)
                .hasMessageStartingWith(ORIGIN + ": ")
                .hasMessageContaining("the file holds public keys and addresses only")
                .hasMessageNotContaining("-----BEGIN");
    }

    @Test
    void yaml_aliases_do_not_hide_private_key_markers() {
        refuses(
                "transports: {matrix: {first: &key 'PRIVATE KEY', second: *key}}",
                "transports.matrix.first",
                "public keys and addresses only");
    }

    @Test
    void a_binary_value_cannot_hide_a_private_key_marker() {
        refuses(
                "transports: {matrix: {blob: !!binary UFJJVkFURSBLRVk=}}",
                "transports.matrix.blob",
                "public keys and addresses only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"address", "endpoint", "wireguard_public_key", "network", "ca_sha256"})
    void a_refused_host_value_is_never_echoed(final String key) {
        final String value = "secret_pasted_into_a_public_field";
        assertThatThrownBy(
                        () ->
                                read(
                                        HOST.replaceAll(
                                                "(?m)^  " + key + ":.*$",
                                                "  " + key + ": " + value)))
                .as("a refused %s must not store or print the pasted value", key)
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'host." + key + "'")
                .hasMessageNotContaining(value);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not_an_address",
                "10.76.255.255",
                "10.77.0.0",
                "10.77.0.255",
                "10.77.0.1",
                "10.77.0.10"
            })
    void a_refused_vpn_address_is_never_echoed(final String value) {
        final String developers =
                value.equals("10.77.0.10")
                        ? DEVELOPERS
                                + DEVELOPERS
                                        .substring("developers:\n".length())
                                        .replace("alice", "bob")
                        : DEVELOPERS.replace("10.77.0.10", value);
        assertThatThrownBy(() -> read(HOST + developers))
                .as("every address refusal names its field and expectation without its value")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("vpn_address")
                .hasMessageNotContaining(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"private key", "pRiVaTe KeY"})
    void private_key_markers_are_case_insensitive(final String marker) {
        refuses(
                "transports: {room: {key: '" + marker + "'}}",
                "transports.room.key",
                "public keys and addresses only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGE-SECRET-KEY-EXAMPLE", "age-secret-key-example"})
    void age_secret_keys_are_refused(final String marker) {
        refuses(
                "transports: {room: {key: '" + marker + "'}}",
                "transports.room.key",
                "public keys and addresses only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cHJpdmF0ZSBrZXk=", "YWdlLXNlY3JldC1rZXktZXhhbXBsZQ=="})
    void binary_values_cannot_hide_lower_case_private_key_markers(final String encoded) {
        refuses(
                "transports: {room: {key: !!binary " + encoded + "}}",
                "transports.room.key",
                "public keys and addresses only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "true"})
    void a_transport_name_must_be_a_yaml_string(final String key) {
        refuses("transports: {" + key + ": {}}", "transports." + key, "is not a transport's name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "AAAA=", "AAAA==", "AAAAA"})
    void an_ssh_key_blob_must_decode_as_base64(final String blob) {
        refuses(
                HOST.replace("ssh-ed25519 AAAA host@example.org", "ssh-ed25519 " + blob),
                "host.ssh_host_key",
                "is not a public key");
    }

    @Test
    void a_refused_value_is_absent_from_diagnostics() {
        assertThatThrownBy(
                        () ->
                                read(
                                        HOST.replace(
                                                "address: host.example.org",
                                                "address: \"bad\\n\\t\\u0085name\"")))
                .as("an invalid address must not inject terminal output")
                .isInstanceOf(ProjectException.class)
                .hasMessageStartingWith(ORIGIN + ": ")
                .hasMessageContaining("'host.address'")
                .hasMessageContaining("is not a DNS name or IP address")
                .hasMessageNotContaining("bad")
                .hasMessageNotContaining("\n")
                .hasMessageNotContaining("\t")
                .hasMessageNotContaining("\u0085");
    }

    @Test
    void control_characters_in_a_key_are_replaced_in_diagnostics() {
        assertThatThrownBy(() -> read("transports: {\"bad\\nname\": {}}"))
                .as("key paths are untrusted text too")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'transports.bad?name'")
                .hasMessageNotContaining("\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"202E", "2028", "2029"})
    void refused_values_with_invisible_formatting_are_absent_from_diagnostics(final String code) {
        assertThatThrownBy(
                        () ->
                                read(
                                        HOST.replace(
                                                "address: host.example.org",
                                                "address: \"bad\\u" + code + "name\"")))
                .as("a rejected value must not reorder or split the diagnostic")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is not a DNS name or IP address")
                .hasMessageNotContaining("bad");
    }
}
