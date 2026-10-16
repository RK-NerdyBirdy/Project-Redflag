package edu.redflag.auth;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe singleton session context holding the currently authenticated {@link User}
 * for the Project Redflag edge gateway.
 */
public final class SessionContext {

    private static final AtomicReference<User> current = new AtomicReference<>();

    private SessionContext() {
        // Prevent instantiation of utility context
    }

    /**
     * Sets the currently authenticated user in the global session context.
     *
     * @param u the logged-in user record
     */
    public static void login(final User u) {
        current.set(u);
    }

    /**
     * Clears the currently authenticated user session.
     */
    public static void logout() {
        current.set(null);
    }

    /**
     * Retrieves the currently authenticated user, if present.
     *
     * @return an {@link Optional} containing the logged-in user, or empty if none
     */
    public static Optional<User> current() {
        return Optional.ofNullable(current.get());
    }

    /**
     * Checks whether the current user has the 'ADMIN' role.
     *
     * @return {@code true} if a user is logged in and their role matches 'ADMIN' (case-insensitive);
     *         {@code false} otherwise
     */
    public static boolean isAdmin() {
        final var u = current.get();
        return u != null && "ADMIN".equalsIgnoreCase(u.role());
    }

    /**
     * Returns the email address of the currently authenticated user, or "Unknown" if not logged in.
     *
     * @return email string or "Unknown"
     */
    public static String email() {
        final var u = current.get();
        return (u != null && u.email() != null && !u.email().isBlank()) ? u.email() : "Unknown";
    }
}
