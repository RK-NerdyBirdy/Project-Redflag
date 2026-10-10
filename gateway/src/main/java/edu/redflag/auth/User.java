package edu.redflag.auth;

public record User(int id, String email, String pwdHash, String role) {}
