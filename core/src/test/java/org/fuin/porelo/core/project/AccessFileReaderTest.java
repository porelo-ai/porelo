package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against applying ambiguous access settings or exposing private keys. */
class AccessFileReaderTest {

    private static final String ORIGIN = "checkout/access.yml";
    private static final String HOST =
            """
            host:
              address: host.example.org
              ssh_host_key: ssh-ed25519 AAAA host@example.org
            """;

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
                .isEqualTo(new AccessFile(null, Map.of(), List.of(), Map.of()));
    }

    @Test
    void a_host_can_be_named_without_persons_or_transports() {
        final AccessFile access = read(HOST);
        assertThat(access.host()).as("a host need not name persons").isNotNull();
        assertThat(access.persons()).as("no person is invented").isEmpty();
        assertThat(access.transports()).as("no transport is invented").isEmpty();
    }

    @Test
    void empty_persons_and_transports_do_not_require_a_host() {
        assertThat(read("persons: {}\ntransports: {}"))
                .as("empty optional sections name nothing")
                .isEqualTo(new AccessFile(null, Map.of(), List.of(), Map.of()));
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
                "persons: {alice: {}, alice: {}}",
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
    @ValueSource(strings = {"address", "ssh_host_key"})
    void every_host_key_is_required(final String key) {
        refuses(HOST.replaceAll("(?m)^  " + key + ":.*\\n", ""), "host." + key, "is required");
    }

    @Test
    void an_unknown_host_key_is_refused() {
        refuses(HOST + "  extra: value\n", "host.extra", "is not known");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "text"})
    void a_host_section_that_is_not_a_mapping_is_refused(final String value) {
        refuses("host: " + value, "host", "must be a mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "''", "42", "true", "[]", "{}"})
    void a_required_field_must_be_nonempty_text(final String value) {
        refuses(
                HOST.replace("address: host.example.org", "address: " + value),
                "host.address",
                "is required");
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
                "persons: {alice: {fingerprint: 'PRIVATE KEY'}}",
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
    @ValueSource(strings = {"address"})
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
