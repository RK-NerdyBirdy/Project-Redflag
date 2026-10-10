package edu.redflag.web;

import edu.redflag.auth.User;
import edu.redflag.auth.UserSession;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class AuthServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String email = req.getParameter("email");
        String pwd = req.getParameter("password");

        if ("admin@example.com".equals(email) && "password".equals(pwd)) {
            User u = new User(1, email, "hash", "ADMIN");
            String token = UserSession.generateToken(u);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"token\":\"" + token + "\"}");
        } else {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write("{\"error\":\"Invalid credentials\"}");
        }
    }
}
