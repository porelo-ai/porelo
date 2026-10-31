package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the one rule no URL printed or kept passes without: a word with an {@code @} loses all up
 * to its last one, but for a scheme it begins with. It leaves out more than a user part by design.
 */
class UserinfoTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // A token holding an @ and a :// of its own.
                "https://user:a@sec1://sec2@forge.example.org/r.git|https://forge.example.org/r.git",
                // No scheme at the word's start: a :// in the token is none.
                "user:s3c://ret@host:o/r|host:o/r",
                "user:s3c://ret@forge.example.org:o/r.git|forge.example.org:o/r.git",
                // An IPv6 host with a zone id.
                "https://user:t@[fe80::1%25eth0]/r.git|https://[fe80::1%25eth0]/r.git",
                "user:t0ken@[::1]:org/repo|[::1]:org/repo",
                // An origin that names another URL after its user part.
                "user:tok3n@host:org/reg?x=https://y|host:org/reg?x=https://y",
                "user:t0ken@host:repo->https://x|host:repo->https://x",
                // A token with a slash, an scp address, a port.
                "https://alice:to/k3n@forge.example.org/acme/r.git|https://forge.example.org/acme/r.git",
                "alice:s3cret@forge.example.org:acme/r.git|forge.example.org:acme/r.git",
                "ssh://git:t@forge.example.org:2222/o/r|ssh://forge.example.org:2222/o/r",
                "git+ssh://u:p@h/r|git+ssh://h/r",
                // A mail address keeps its domain only.
                "a@example.org|example.org",
                "a@example.org,|example.org,"
            })
    void all_up_to_the_last_at_is_left_out_but_a_leading_scheme(
            final String word, final String left) {
        assertThat(Userinfo.leftOutOf(word)).isEqualTo(left);
        assertThat(Userinfo.leftOutOf("said " + word + " here"))
                .isEqualTo("said " + left + " here");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'", "\"", ")", ".", ",", "?", "#", ";", "]", ">"})
    void punctuation_after_the_host_keeps_no_token(final String after) {
        assertThat(Userinfo.leftOutOf("'https://user:tok3n@forge.example.org" + after))
                .isEqualTo("forge.example.org" + after);
    }

    @Test
    void two_urls_in_a_word_keep_no_secret() {
        assertThat(
                        Userinfo.leftOutOf(
                                "https://u:t@k/x@forge.example.org/r,u2:s3@192.0.2.1:o/r"
                                        + " https://a.example.org/r,https://user:tok3n@b.example.org/r"))
                .doesNotContain("t@k", "s3", "tok3n", "user", "u2")
                .isEqualTo("https://192.0.2.1:o/r https://b.example.org/r");
    }

    @Test
    void a_word_without_an_at_is_as_it_was() {
        final String text = "git said https://forge.example.org/r.git: 'ref://x', 50% done\n";
        assertThat(Userinfo.leftOutOf(text)).isEqualTo(text);
    }
}
