package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the one address rule and the one cut. Harm: a token taken into a file Porelo accepts, kept
 * in its trust record and answered, or handed to ssh on its command line; and an address with no
 * credential changed, so a caller fetches from somewhere else than its checkout names.
 */
class AddressFormTest {

    /**
     * The registry and the access file name no credential: a token comes from the vault, chosen by
     * the host. An https user part may be a token by itself, git decodes a {@code %} spelling and
     * hands ssh the password, and a user beginning with {@code -} is no user.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://bob:tok-4711@example.org/demo.git",
                "https://bob:tok%404711@example.org/demo.git",
                "https://tok-4711@example.org/demo.git",
                "ssh://git:tok-4711@example.org/demo.git",
                "ssh://git:tok:4711@example.org:2222/demo.git",
                "ssh://git%3Atok-4711@example.org/demo.git",
                "ssh://git%40tok-4711@example.org/demo.git",
                "ssh://-oProxyCommand=x@example.org/demo.git",
                "-oProxyCommand=x@example.org:demo.git"
            })
    void an_address_holding_a_credential_or_an_option_as_its_user_is_refused(String address) {
        assertThat(AddressForm.isRepository(address)).as(address).isFalse();
    }

    /**
     * git decodes a {@code %} spelling in a bracketed host as well and hands ssh the brackets'
     * contents, so {@code [a:tok%40example.org]} reaches ssh as user {@code a:tok} at {@code
     * example.org}: only {@code %25} or a zone git leaves as it is starts a zone, and only an IPv6
     * address, which has its colons, stands in brackets.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://[a:c0ffee%40example.org]/demo.git",
                "ssh://git@[c0ffee0123456789%40forge.example.org]/demo.git",
                "ssh://git@[fe80::1%40example.org]/demo.git",
                "https://[c0ffee0123456789%40forge.example.org]/demo.git",
                "ssh://git@[c0ffee0123456789%eth0]/demo.git"
            })
    void a_bracketed_host_that_is_no_ipv6_address_or_spells_an_at_sign_is_refused(String address) {
        assertThat(AddressForm.isRepository(address)).as(address).isFalse();
        assertThat(AddressForm.isHttps(address)).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://[c0ffee0123456789%40wharf6.example.org]",
                "https://[a:c0ffee%40wharf6.example.org]:8448"
            })
    void a_homeserver_whose_bracketed_host_spells_an_at_sign_is_refused(String url) {
        assertThat(AddressForm.isHttps(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://bob:tok-4711@wharf6.example.org:8448",
                "https://tok-4711@wharf6.example.org",
                "https://bob:tok%404711@wharf6.example.org"
            })
    void a_homeserver_with_a_user_part_is_refused(String url) {
        assertThat(AddressForm.isHttps(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "ssh://-oProxyCommand=x:tok-4711@example.org/demo.git, ssh://example.org/demo.git",
        "ssh://git%3Atok-4711@example.org/demo.git, ssh://example.org/demo.git",
        "ssh://git:tok-4711@example.org/demo.git, ssh://git@example.org/demo.git"
    })
    void an_ssh_user_that_is_no_plain_name_is_cut(String address, String kept) {
        assertThat(AddressForm.withoutUserinfo(address)).isEqualTo(kept);
    }

    /**
     * git decodes {@code %40} and ssh takes the user up to the last at sign, so after a plain user
     * the host {@code bob%3Atok%40example.org} is user {@code git@bob:tok} at {@code example.org}.
     */
    @ParameterizedTest
    @CsvSource({
        "ssh://git@bob%3Atok-4711%40example.org/demo.git, ssh:///demo.git",
        "ssh://git@bob%3atok-4711%40example.org:2222/demo.git, ssh:///demo.git",
        "ssh://git@[a:tok-4711%40example.org]/demo.git, ssh:///demo.git"
    })
    void an_authority_spelling_its_at_sign_after_a_plain_user_is_left_out_whole(
            String address, String kept) {
        assertThat(AddressForm.withoutUserinfo(address)).isEqualTo(kept);
    }

    /**
     * git splits {@code user@host:path} after a bracketed host, and ssh at its last {@code @}; read
     * either way, what stands before the host is a user and a password.
     */
    @ParameterizedTest
    @CsvSource({
        "bob:tok-4711@[2001:db8::1]:demo.git, [2001:db8::1]:demo.git",
        "git:tok-4711@[fe80::1%eth0]:demo.git, [fe80::1%eth0]:demo.git",
        "bob:tok-4711@example.org:demo.git, example.org:demo.git",
        "bob:tok-4711@example.org:acme/demo.git, example.org:acme/demo.git",
        "bob:tok/4711@example.org:demo.git, example.org:demo.git",
        "bob:tok/4711@[2001:db8::1]:demo.git, [2001:db8::1]:demo.git",
        "bob:tok/47@11@example.org:acme/demo.git, example.org:acme/demo.git",
        "git@example.org:tok-4711@[2001:db8::1]:demo.git, [2001:db8::1]:demo.git"
    })
    void a_password_before_the_host_of_user_at_host_path_is_cut(String address, String kept) {
        assertThat(AddressForm.withoutUserinfo(address)).isEqualTo(kept);
    }

    /**
     * A base URL holds no query or fragment; one that did could carry a token past the user part's
     * cut, or read as a user part to a reader that takes the last {@code @}.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://wharf6.example.org?x=/bob:tok-4711@evil.example.org",
                "https://wharf6.example.org#/bob:tok-4711@evil.example.org",
                "https://wharf6.example.org/?access_token=tok-4711",
                "https://wharf6.example.org:8448/x#tok-4711"
            })
    void a_homeserver_with_a_query_or_a_fragment_is_refused(String url) {
        assertThat(AddressForm.isHttps(url)).as(url).isFalse();
    }

    /** No blank or control character of any script passes, as an ASCII blank does not. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.org/demo.git\u00a0",
                "ssh://git@example.org/de\u2028mo.git",
                "git@example.org:acme/\u001bdemo.git",
                "\u00a0https://example.org/demo.git",
                "git\u00a0bob@example.org:acme/demo.git"
            })
    void an_address_with_a_unicode_blank_or_control_is_refused(String address) {
        assertThat(AddressForm.isRepository(address)).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://wharf6.example.org/\u00a0",
                "https://wharf6.example.org/x\u2028y",
                "https://wharf6.example.org/\u001b[2J",
                "https://wharf6.example.org\u0085"
            })
    void a_homeserver_with_a_unicode_blank_or_control_is_refused(String url) {
        assertThat(AddressForm.isHttps(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\u00a0https://bob:tok-4711@example.org/demo.git",
                "\u2007ssh://git:tok-4711@example.org/demo.git",
                "git\u00a0tok-4711@example.org:demo.git",
                "git\u0007tok-4711@example.org:demo.git",
                "\u0085bob:tok-4711@example.org:demo.git"
            })
    void a_unicode_blank_or_control_hides_no_credential_from_the_cut(String address) {
        assertThat(AddressForm.withoutUserinfo(address)).doesNotContain("tok-4711");
    }

    /**
     * Only a credential is cut: what git fetches from an address that holds none stays the same, an
     * IPv6 zone, a Windows user, and forms git cannot fetch alike.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@[fe80::1%eth0]/repo.git",
                "ssh://[fe80::1%eth0]/repo.git",
                "git+ssh://git@[fe80::1%eth0]/repo.git",
                "ssh://git@[fe80::1%25eth0]/repo.git",
                "ssh://git@example.org:~alice/repo.git",
                "file://./repo.git",
                "DOMAIN\\alice@example.org:repo.git",
                "git@[fe80::1%eth0]:demo.git",
                "git@example.org:acme/demo.git",
                "git@example.org:team@v2:repo.git",
                "git@[2001:db8::1]:team@v2:repo.git",
                "[2001:db8::1]:acme/demo.git",
                "ssh://git@example.org:2222/demo.git",
                "https://example.org/acme/demo.git",
                "/srv/git/demo.git"
            })
    void an_address_without_a_credential_is_kept_as_given(String address) {
        assertThat(AddressForm.withoutUserinfo(address)).isEqualTo(address);
    }

    /** git reads each of these as an ssh address, never as a remote helper's transport. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "git@[::1]:demo.git",
                "git@[fe80::1]:demo.git",
                "git@[fd00::1]:acme/demo.git",
                "git@[fe80::1%eth0]:demo.git",
                "ssh://git@[fe80::1%eth0]/demo.git",
                "ssh://git@[fe80::1%25eth0]:2222/demo.git",
                "DOMAIN\\alice@example.org:repo.git"
            })
    void an_ipv6_address_git_fetches_is_taken(String address) {
        assertThat(AddressForm.isRepository(address)).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ext::sh -c x", "fd::3", "foo::bar", "git+x.y-z::example.org:demo"})
    void a_remote_helpers_transport_is_refused(String address) {
        assertThat(AddressForm.isRepository(address)).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTPS://wharf6.example.org:8448",
                "Https://wharf6.example.org",
                "https://cafe\u0301.example.org",
                "https://wharf6.example.org/_matrix/@room",
                "https://[2001:db8::1]:8448/"
            })
    void a_homeserver_any_https_reader_takes_is_taken(String url) {
        assertThat(AddressForm.isHttps(url)).as(url).isTrue();
    }
}
