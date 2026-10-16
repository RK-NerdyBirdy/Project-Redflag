package edu.redflag.auth;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Utility wrapper around jBCrypt providing cryptographic password hashing and verification
 * for Project Redflag user authentication.
 */
public final class PasswordUtil {

    private PasswordUtil() {
        // Prevent instantiation
    }

    /**
     * Generates a BCrypt hash for the provided plaintext password using a logarithmic cost factor of 12.
     *
     * @param plain the plaintext password to hash
     * @return the resulting BCrypt salt-and-hash string
     * @throws IllegalArgumentException if the plaintext password is null
     */
    public static String hash(final String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("Plaintext password cannot be null.");
        }
        return BCrypt.hashpw(plain, BCrypt.gensalt(12));
    }

    /**
     * Verifies whether a plaintext password matches an existing BCrypt hash.
     *
     * @param plain the plaintext password candidate
     * @param hash  the stored BCrypt hash
     * @return {@code true} if the plaintext matches the hash; {@code false} otherwise
     */
    public static boolean verify(final String plain, final String hash) {
        if (plain == null || hash == null) {
            return false;
        }
        if (!isHashed(hash)) {
            // Backward-compatible fallback for legacy plain-text test accounts
            return plain.equals(hash);
        }
        try {
            return BCrypt.checkpw(plain, hash);
        } catch (Exception e) {
            System.err.println("[PasswordUtil] Password verification error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a given string conforms to standard BCrypt hash prefixes ("$2a$", "$2b$", or "$2y$").
     *
     * @param s the string to inspect
     * @return {@code true} if the string begins with a known BCrypt identifier prefix; {@code false} otherwise
     */
    public static boolean isHashed(final String s) {
        if (s == null) {
            return false;
        }
        return s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$");
    }
}
