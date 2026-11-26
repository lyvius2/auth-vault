package banghak.home.authvault.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    @Test
    void tokenIsSingleUse() {
        TokenService tokens = new TokenService(TestProps.of(TestProps.KEY, "unused", 10));
        String t = tokens.issue("yunson", "catchtable");

        assertThat(tokens.peek(t)).isPresent();          // peek 은 소모하지 않는다
        var p = tokens.consume(t);
        assertThat(p).isPresent();
        assertThat(p.get().user()).isEqualTo("yunson");
        assertThat(p.get().site()).isEqualTo("catchtable");

        assertThat(tokens.consume(t)).isEmpty();
        assertThat(tokens.peek(t)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        TokenService tokens = new TokenService(TestProps.of(TestProps.KEY, "unused", -1));   // 발급 즉시 만료
        String t = tokens.issue("yunson", "catchtable");

        assertThat(tokens.peek(t)).isEmpty();
        assertThat(tokens.consume(t)).isEmpty();
    }

    @Test
    void tokensAreUniqueAndUnknownTokenIsRejected() {
        TokenService tokens = new TokenService(TestProps.of(TestProps.KEY, "unused", 10));
        assertThat(tokens.issue("yunson", "catchtable")).isNotEqualTo(tokens.issue("yunson", "catchtable"));
        assertThat(tokens.consume("nope")).isEmpty();
    }
}
