package edu.redflag.web;

import edu.redflag.auth.PasswordUtil;
import edu.redflag.auth.User;
import edu.redflag.auth.UserSession;
import edu.redflag.persist.Db;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * HTTP Servlet handling user authentication requests via JSON web tokens (JWT)
 * backed by database verification using BCrypt password hashing.
 */
public class AuthServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        final var email = req.getParameter("email");
        final var pwd = req.getParameter("password");

        if (email == null || pwd == null) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Email and password are required\"}");
            return;
        }

        // Query users table for email match, verify BCrypt hash
        try (final Connection conn = Db.get();
             final PreparedStatement ps = conn.prepareStatement("SELECT id, email, pwd_hash, role FROM users WHERE email = ?")) {
            ps.setString(1, email);
            final ResultSet rs = ps.executeQuery();
            if (rs.next() && PasswordUtil.verify(pwd, rs.getString("pwd_hash"))) {
                final var u = new User(rs.getInt("id"), rs.getString("email"), rs.getString("pwd_hash"), rs.getString("role"));
                final var token = UserSession.generateToken(u);
                resp.setContentType("application/json");
                resp.getWriter().write("{\"token\":\"" + token + "\",\"role\":\"" + u.role() + "\"}");
            } else {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json");
                resp.getWriter().write("{\"error\":\"Invalid credentials\"}");
            }
        } catch (SQLException e) {
            System.err.println("[AuthServlet] Database authentication error: " + e.getMessage());
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Internal authentication failure\"}");
        }
    }
}
