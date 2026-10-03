package io.swoc2.app.connections;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored secrets such as connection passwords (ARCHITECTURE §10, §12
 * {@code SWOC2_SECRET_KEY}) with AES-256-GCM (JDK only, no extra dependency). The key is derived
 * from the configured secret with SHA-256 (domain-separated). Values are stored as
 * {@code enc:v1:<base64(iv | ciphertext+tag)>}. Without a configured key, secrets cannot be saved -
 * the API says so instead of silently storing plaintext.
 */
@Component
public class SecretBox {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    SecretBox(@Value("${swoc2.secret-key:}") String secret) {
        this.key = secret == null || secret.isBlank() ? null : derive(secret);
    }

    private static SecretKeySpec derive(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("swoc2-secret-box-v1".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(sha.digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean available() {
        return key != null;
    }

    public static boolean isEncrypted(Object value) {
        return value instanceof String s && s.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (key == null) {
            throw new IllegalStateException("SWOC2_SECRET_KEY is not set; secrets cannot be stored");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(iv.length + ct.length)
                                    .put(iv)
                                    .put(ct)
                                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Decrypts a stored value; plain values (legacy/tests) are returned unchanged. */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("SWOC2_SECRET_KEY is not set; stored secrets cannot be read");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored secret cannot be decrypted (wrong SWOC2_SECRET_KEY?)", e);
        }
    }
}
