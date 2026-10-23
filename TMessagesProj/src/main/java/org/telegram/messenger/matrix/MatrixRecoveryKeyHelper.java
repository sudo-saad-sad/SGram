package org.telegram.messenger.matrix;

import android.text.TextUtils;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MatrixRecoveryKeyHelper {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);
    private static final byte HEADER_BYTE_1 = (byte) 0x8B;
    private static final byte HEADER_BYTE_2 = (byte) 0x01;

    // ---------------- Base58 Encoding / Decoding ----------------

    public static String encodeBase58(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }

        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }

        BigInteger value = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();

        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = value.divideAndRemainder(BASE);
            value = divRem[0];
            int rem = divRem[1].intValue();
            sb.append(ALPHABET.charAt(rem));
        }

        for (int i = 0; i < leadingZeros; i++) {
            sb.append(ALPHABET.charAt(0));
        }

        return sb.reverse().toString();
    }

    public static byte[] decodeBase58(String input) {
        if (TextUtils.isEmpty(input)) {
            return new byte[0];
        }

        int leadingZeros = 0;
        while (leadingZeros < input.length() && input.charAt(leadingZeros) == ALPHABET.charAt(0)) {
            leadingZeros++;
        }

        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                return null; // Invalid character
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }

        byte[] rawBytes = value.toByteArray();
        int stripSign = (rawBytes.length > 0 && rawBytes[0] == 0) ? 1 : 0;
        int payloadLen = rawBytes.length - stripSign;

        byte[] result = new byte[leadingZeros + payloadLen];
        System.arraycopy(rawBytes, stripSign, result, leadingZeros, payloadLen);
        return result;
    }

    // ---------------- Recovery Key (MSC2474) ----------------

    /**
     * Encodes a 32-byte private key into the standard Matrix Recovery Key format (MSC2474):
     * [0x8B] [0x01] [32 bytes private key] [1 byte cumulative XOR parity] -> 35 bytes Base58.
     * Always produces a 48-character string beginning with "Es".
     */
    public static String encodeRecoveryKey(byte[] privateKey32) {
        if (privateKey32 == null || privateKey32.length != 32) {
            throw new IllegalArgumentException("Private key must be 32 bytes");
        }

        byte[] full = new byte[35];
        full[0] = HEADER_BYTE_1;
        full[1] = HEADER_BYTE_2;
        System.arraycopy(privateKey32, 0, full, 2, 32);

        // Parity checksum: XOR of all 34 preceding bytes
        byte parity = 0;
        for (int i = 0; i < 34; i++) {
            parity ^= full[i];
        }
        full[34] = parity;

        String b58 = encodeBase58(full);

        // Format in 4-character blocks separated by spaces: EsXX XXXX ...
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < b58.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append(' ');
            }
            formatted.append(b58.charAt(i));
        }
        return formatted.toString();
    }

    /**
     * Decodes a Recovery Key string (MSC2474), validates header 0x8B 0x01 and XOR parity,
     * and returns the 32-byte Curve25519 private key.
     */
    public static byte[] decodeRecoveryKey(String inputKey) {
        if (TextUtils.isEmpty(inputKey)) {
            return null;
        }

        // Clean spaces, hyphens, and newlines
        String cleaned = inputKey.replaceAll("[\\s\\-]+", "");

        // 1. Check MSC2474 standard Base58 key
        byte[] decoded = decodeBase58(cleaned);
        if (decoded != null && decoded.length == 35) {
            if (decoded[0] == HEADER_BYTE_1 && decoded[1] == HEADER_BYTE_2) {
                byte expectedParity = 0;
                for (int i = 0; i < 34; i++) {
                    expectedParity ^= decoded[i];
                }
                if (decoded[34] == expectedParity) {
                    return Arrays.copyOfRange(decoded, 2, 34);
                }
            }
        }

        // 2. Fallback: check if raw 32-byte Base64 key was supplied
        try {
            byte[] rawB64 = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT);
            if (rawB64 != null && rawB64.length == 32) {
                return rawB64;
            }
        } catch (Exception ignore) {}

        return null;
    }

    // ---------------- Passphrase Key Derivation (PBKDF2) ----------------

    public static byte[] deriveKeyFromPassphrase(String passphrase, byte[] salt, int iterations) {
        if (TextUtils.isEmpty(passphrase)) {
            return null;
        }
        try {
            if (salt == null || salt.length == 0) {
                salt = "m.megolm_backup.v1".getBytes(StandardCharsets.UTF_8);
            }
            if (iterations <= 0) {
                iterations = 500_000;
            }
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            try {
                PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
                SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                return skf.generateSecret(spec).getEncoded();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static byte[] generateRandomPrivateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        key[0] &= 248;
        key[31] &= 127;
        key[31] |= 64;
        return key;
    }
}
