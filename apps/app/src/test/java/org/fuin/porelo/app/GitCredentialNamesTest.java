package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which vault entry a git URL reaches for.
 *
 * <p>The scheme decides the kind and the host decides which one. Both halves are measured here
 * because both are the whole interface a person sees: the name they type into {@code porelo vault
 * put} is the name this resolves.
 */
class GitCredentialNamesTest {

    @Test
    void theSchemeDecidesTheKind() {
        assertThat(GitCredentialNames.kindOf("git@github.com:acme/x.git"))
                .isEqualTo(GitCredentialNames.Kind.KEY);
        assertThat(GitCredentialNames.kindOf("ssh://git@gitlab.company.example/acme/x.git"))
                .isEqualTo(GitCredentialNames.Kind.KEY);
        assertThat(GitCredentialNames.kindOf("https://github.com/acme/x.git"))
                .isEqualTo(GitCredentialNames.Kind.TOKEN);
        // A directory on this machine is opened by neither, and saying otherwise would send
        // somebody to store a credential that changes nothing.
        assertThat(GitCredentialNames.kindOf("/home/alice/git/acme"))
                .isEqualTo(GitCredentialNames.Kind.NONE);
    }

    @Test
    void theHostIsReadOffEitherShapeOfUrl() {
        assertThat(GitCredentialNames.hostOf("git@github.com:acme/x.git")).isEqualTo("github.com");
        assertThat(GitCredentialNames.hostOf("https://gitlab.company.example/acme/x.git"))
                .isEqualTo("gitlab.company.example");
        assertThat(GitCredentialNames.hostOf("https://someone@forge.example:8443/x.git"))
                .isEqualTo("forge.example");
        assertThat(GitCredentialNames.hostOf("/home/alice/git/acme")).isNull();
    }

    @Test
    void anIpv6HostIsReadWholeSoNoOtherHostsEntryIsTried() {
        // Cut at its first colon, every host of 2001:db8::/32 was '[2001': one entry served them
        // all, and git matched no helper keyed by it.
        assertThat(GitCredentialNames.hostOf("https://[2001:db8::1]/acme/demo.git"))
                .isEqualTo("[2001:db8::1]");
        assertThat(GitCredentialNames.hostOf("https://bob@[2001:db8::1]:8443/acme/demo.git"))
                .isEqualTo("[2001:db8::1]");
        assertThat(GitCredentialNames.hostOf("ssh://git@[::1]:2222/acme/demo.git"))
                .isEqualTo("[::1]");
        assertThat(GitCredentialNames.hostOf("git@[2001:db8::1]:acme/demo.git"))
                .isEqualTo("[2001:db8::1]");
        assertThat(GitCredentialNames.candidatesFor("https://[2001:db8::2]/acme/demo.git"))
                .as("the entry of the host git connects to, and no other host's")
                .containsExactly("git.token.[2001:db8::2]", "git.token");
    }

    @Test
    void anAtInThePathNeverNamesAnotherHostsCredential() {
        // The host is the authority's: read past the first '/', this address took github.com's key,
        // and the agent the key was lent through answered forge.example.org with it.
        assertThat(
                        GitCredentialNames.candidatesFor(
                                "ssh://git@forge.example.org/team/@github.com/x.git"))
                .as("the key for the host git connects to")
                .containsExactly("git.ssh.forge.example.org", "git.ssh", "ssh.default");
        assertThat(GitCredentialNames.hostOf("https://forge.example.org/x/@github.com/y.git"))
                .isEqualTo("forge.example.org");
    }

    @Test
    void theHostKeyedEntryIsTriedBeforeThePlainOne() {
        // A company forge and a public one are two credentials. A machine that fell back to one key
        // for both would offer a company's credential to whatever host a project file names, which
        // is the kind of thing nobody notices until it matters.
        assertThat(GitCredentialNames.candidatesFor("git@github.com:acme/x.git"))
                .containsExactly("git.ssh.github.com", "git.ssh", "ssh.default");
        assertThat(GitCredentialNames.candidatesFor("https://github.com/acme/x.git"))
                .containsExactly("git.token.github.com", "git.token");
        assertThat(GitCredentialNames.candidatesFor("/home/alice/git/acme")).isEmpty();
    }

    @Test
    void whatItTellsSomebodyToTypeIsWhatItWouldThenRead() {
        // The commonest way to waste an afternoon is a message naming a name nothing looks in.
        final String url = "https://gitlab.company.example/acme/x.git";
        assertThat(GitCredentialNames.storeCommandFor(url))
                .isEqualTo("porelo vault put git.token.gitlab.company.example");
        assertThat(GitCredentialNames.candidatesFor(url).get(0))
                .isEqualTo("git.token.gitlab.company.example");

        final String ssh = "git@github.com:acme/x.git";
        assertThat(GitCredentialNames.storeCommandFor(ssh))
                .startsWith("porelo vault put " + GitCredentialNames.candidatesFor(ssh).get(0))
                // A key is a file, and the commonest mistake is pasting its public half.
                .contains("< <the private key file>");
    }

    @Test
    void aGitCredentialIsNotAProvidersName() {
        // 'vault put ssh.default' used to warn that no provider was declared under that name, which
        // sent people looking for a mistake they had not made.
        assertThat(GitCredentialNames.isOne("git.token.github.com")).isTrue();
        assertThat(GitCredentialNames.isOne("git.ssh")).isTrue();
        assertThat(GitCredentialNames.isOne("ssh.default")).isTrue();
        assertThat(GitCredentialNames.isOne("anthropic")).isFalse();
    }

    @Test
    void namesACredentialNobodyNamed() {

        // A client that says "vault" and no name means "you name it". Writing "" instead produced a
        // record nothing could store into - 'porelo vault put' with no argument - and a 'porelo
        // vault remove ' with nothing after it. Found through the interface's own declare.
        assertThat(
                        GitCredentialNames.impliedName(
                                GitCredentialNames.Kind.KEY, "ssh://github.com/acme/x.git"))
                .isEqualTo("git.ssh.github.com");
        assertThat(
                        GitCredentialNames.impliedName(
                                GitCredentialNames.Kind.TOKEN, "https://gitlab.example/acme/"))
                .isEqualTo("git.token.gitlab.example");
        // And it is the SAME name the fallback looks in, so a credential declared without a name
        // and one stored without a declaration are one entry rather than two that shadow.
        assertThat(GitCredentialNames.candidatesFor("git@github.com:acme/x.git"))
                .contains(
                        GitCredentialNames.impliedName(
                                GitCredentialNames.Kind.KEY, "ssh://github.com/acme/x.git"));
        // A destination with no host cannot be named after one, and is refused rather than recorded
        // namelessly.
        assertThat(GitCredentialNames.impliedName(GitCredentialNames.Kind.KEY, "/a/path")).isNull();
    }

    @Test
    void theScopeOfAnAddressIsItsSchemeHostAndPortAsWritten() {
        // The key git matches a helper by: a port dropped from it misses the address, so the fetch
        // goes out without the token, and anything more than the authority misses as well.
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example:8443/team/repo.git"))
                .as("the key git compares with the address it connects to")
                .isEqualTo("https://forge.example:8443");
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example:443/team/repo.git"))
                .as("the key git compares with the address it connects to")
                .isEqualTo("https://forge.example:443");
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example/team/repo.git"))
                .as("the key git compares with the address it connects to")
                .isEqualTo("https://forge.example");
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example"))
                .as("the key git compares with the address it connects to")
                .isEqualTo("https://forge.example");
        assertThat(GitCredentialNames.helperScopeOf("https://[::1]:8443/team/repo.git"))
                .as("the key git compares with the address it connects to")
                .isEqualTo("https://[::1]:8443");
        assertThat(GitCredentialNames.helperScopeOf("https://[2001:db8::1]/team/repo.git"))
                .as("the key git compares with the address it connects to")
                .isEqualTo("https://[2001:db8::1]");
    }

    @Test
    void theScopeHoldsNoUserPathQueryOrFragmentAndNoHostTheyName() {
        assertThat(GitCredentialNames.helperScopeOf("https://someone@forge.example:8443/x.git"))
                .as("a helper keyed by a user misses the same address written without one")
                .isEqualTo("https://forge.example:8443");
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example:8443?a=b"))
                .as("only the authority git connects to")
                .isEqualTo("https://forge.example:8443");
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example/x/@other.example/y.git"))
                .as("the host git connects to, not the one after the path's '@'")
                .isEqualTo("https://forge.example");
    }

    @Test
    void anAddressTwoReadersTakeForTwoHostsHasNoScope() {
        // git connects to the host before the '#' or '?', and the entry and the record are chosen
        // by the host after the '@': that host's token would go to the other one.
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example#@other.example/x.git"))
                .as("the token chosen for other.example must not be keyed for forge.example")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example?@other.example/x.git"))
                .as("the token chosen for other.example must not be keyed for forge.example")
                .isNull();
    }

    @Test
    void anAddressNoTokenTravelsToHasNoScope() {
        // Over http a token would cross the wire in the clear, and an ssh address asks for a key.
        assertThat(GitCredentialNames.helperScopeOf("http://forge.example:8080/x.git"))
                .as("no token is lent to this address")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("ssh://git@forge.example/x.git"))
                .as("no token is lent to this address")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("git@forge.example:x.git"))
                .as("no token is lent to this address")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("/srv/git/x.git"))
                .as("no token is lent to this address")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf(null))
                .as("no token is lent to this address")
                .isNull();
    }

    @Test
    void anAddressNamingNoHostHasNoScope() {
        assertThat(GitCredentialNames.helperScopeOf("https://"))
                .as("no host to lend a token to")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https:///x.git"))
                .as("no host to lend a token to")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https://someone@/x.git"))
                .as("no host to lend a token to")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https://:8443/x.git"))
                .as("no host to lend a token to")
                .isNull();
    }

    @Test
    void anAddressThatWouldRewriteTheHelperSettingHasNoScope() {
        // git reads '-c key=value' up to the first '=', so this host would end the key early and
        // make what it holds the helper git runs, as a shell command.
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example.helper=!id;/x.git"))
                .as("a host holding '=' is not written into a git setting")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https://a@b@forge.example/x.git"))
                .as("git and a reader of the last '@' take two different hosts from this")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https://forge example/x.git"))
                .as("a host holding a blank is not one")
                .isNull();
        assertThat(GitCredentialNames.helperScopeOf("https://forge.example:port/x.git"))
                .as("a port is digits")
                .isNull();
    }

    @Test
    void anEntrysNameStillNamesTheHostAndNeverAPort() {
        // What somebody stored as git.token.<host> must go on being found for a forge on its port.
        final String url = "https://forge.example:8443/x.git";
        assertThat(GitCredentialNames.hostOf(url))
                .as("an entry name carries no port")
                .isEqualTo("forge.example");
        assertThat(GitCredentialNames.candidatesFor(url))
                .as("an entry name carries no port")
                .containsExactly("git.token.forge.example", "git.token");
        assertThat(GitCredentialNames.impliedName(GitCredentialNames.Kind.TOKEN, url))
                .as("an entry name carries no port")
                .isEqualTo("git.token.forge.example");
        assertThat(GitCredentialNames.storeCommandFor(url))
                .as("an entry name carries no port")
                .isEqualTo("porelo vault put git.token.forge.example");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://forge.example%2F@other.example/x.git",
                "ssh://git@forge.example%2F@other.example/x.git",
                "ssh://forge.example%2f@other.example/x.git"
            })
    void anSshAddressGitDecodesToAnotherHostNamesNoHostToLendTo(final String url) {
        // git decodes the address before it ends the host, so it connects to forge.example, while
        // the key is chosen by the host after the '@'.
        assertThat(GitCredentialNames.namesOneHost(url)).as("two hosts in one address").isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@forge.example/x@[other.example]/r.git",
                "ssh://git@forge.example/x%40%5Bother.example%5D/r.git",
                "ssh://forge.example/x@%5bother.example]/r.git",
                "git@forge.example:x@[other.example]:r.git",
                "forge.example:x@[other.example]:r.git"
            })
    void anAddressWhosePathGitTurnsIntoAnotherHostNamesNoHostToLendTo(final String url) {
        // git takes what follows "@[" for a bracketed part and hands ssh "forge.example/x@other",
        // which ssh connects to as the host after the last '@'; the key is chosen for
        // forge.example.
        assertThat(GitCredentialNames.namesOneHost(url)).as("two hosts in one address").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ssh://git@[::1]:2222/x.git", "git@[::1]:x.git"})
    void aBracketedHostNamesOne(final String url) {
        assertThat(GitCredentialNames.namesOneHost(url)).as("an IPv6 host").isTrue();
    }

    @Test
    void aHostGitWouldReadAsAPatternHasNoScope() {
        // git reads '*' in a helper's key as a wildcard, so that one helper answers for other
        // hosts.
        assertThat(GitCredentialNames.helperScopeOf("https://*.example/r.git")).isNull();
    }

    @Test
    void anAddressOverPlainHttpHasNoScopeThoughItsHostAgrees() {
        assertThat(GitCredentialNames.helperScopeOf("http://u@forge.example.org:8080/r.git"))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@forge.example/x%20y.git",
                "git@forge.example:x%2Fy.git",
                "https://forge.example:8443/x.git",
                "/srv/git/x%2Fy.git"
            })
    void anAddressGitReadsAsOneHostNamesOne(final String url) {
        assertThat(GitCredentialNames.namesOneHost(url)).as("the host git connects to").isTrue();
    }
}
