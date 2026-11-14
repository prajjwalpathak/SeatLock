package com.seatlock.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RsaKeysTest {

    private static final KeyPair PAIR = RsaKeys.generate();

    @Test
    void parsesFullPem() {
        KeyPair parsed = RsaKeys.parse(pem("PRIVATE KEY", PAIR.getPrivate().getEncoded()),
                pem("PUBLIC KEY", PAIR.getPublic().getEncoded()));
        assertThat(parsed.getPublic()).isEqualTo(PAIR.getPublic());
    }

    @Test
    void parsesSingleLineBase64AndLiteralBackslashN() {
        String privateKey = b64(PAIR.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", PAIR.getPublic().getEncoded()).replace("\n", "\\n");
        assertThat(RsaKeys.parse(privateKey, publicKey).getPublic()).isEqualTo(PAIR.getPublic());
    }

    @Test
    void rejectsMismatchedPair() {
        KeyPair other = RsaKeys.generate();
        assertThatThrownBy(() -> RsaKeys.parse(b64(PAIR.getPrivate().getEncoded()), b64(other.getPublic().getEncoded())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsKeysShorterThan2048Bits() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair weak = generator.generateKeyPair();
        assertThatThrownBy(() -> RsaKeys.parse(b64(weak.getPrivate().getEncoded()), b64(weak.getPublic().getEncoded())))
                .hasMessageContaining("at least 2048");
    }

    @Test
    void rejectsGarbageAndMissingHalf() {
        assertThatThrownBy(() -> RsaKeys.parse("not*base64", "also-not"))
                .hasMessageContaining("not valid base64");
        assertThatThrownBy(() -> RsaKeys.parse(b64(PAIR.getPrivate().getEncoded()), " "))
                .hasMessageContaining("Set both");
    }

    private static String b64(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
