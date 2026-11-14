package com.seatlock.identity.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Parses and checks the RS256 key pair. Fails fast with a clear message on bad config. */
final class RsaKeys {

    static final int MIN_BITS = 2048;

    private RsaKeys() {
    }

    static KeyPair parse(String privateKeyText, String publicKeyText) {
        if (isBlank(privateKeyText) || isBlank(publicKeyText)) {
            throw new IllegalStateException(
                    "Set both seatlock.jwt.private-key and seatlock.jwt.public-key (JWT_PRIVATE_KEY / JWT_PUBLIC_KEY)");
        }
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(decode(privateKeyText, "private")));
            var publicKey = (RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(decode(publicKeyText, "public")));
            return validated(new KeyPair(publicKey, privateKey));
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalStateException(
                    "JWT keys are not a valid RSA pair (expected PKCS#8 private key + X.509 public key)", ex);
        }
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_BITS);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate an RSA key pair", ex);
        }
    }

    /** Both halves must share a modulus, otherwise every issued token would fail validation. */
    private static KeyPair validated(KeyPair pair) {
        var publicKey = (RSAPublicKey) pair.getPublic();
        var privateKey = (RSAPrivateCrtKey) pair.getPrivate();
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("JWT public key does not match the private key");
        }
        if (publicKey.getModulus().bitLength() < MIN_BITS) {
            throw new IllegalStateException("JWT RSA key must be at least " + MIN_BITS + " bits");
        }
        return pair;
    }

    /** Accepts full PEM, a single base64 line, or PEM squeezed into one line with literal "\n". */
    private static byte[] decode(String text, String which) {
        String base64 = text
                .replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT " + which + " key is not valid base64", ex);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
