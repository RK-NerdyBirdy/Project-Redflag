package edu.redflag.auth;

/**
 * Immutable user account record representing an authenticated entity in Project Redflag.
 *
 * @param id      Unique database identifier
 * @param email   Unique user email address
 * @param pwdHash Secure BCrypt hash of the user's password
 * @param role    Assigned user role (e.g. 'ADMIN', 'VIEWER')
 */
public record User(int id, String email, String pwdHash, String role) {}
