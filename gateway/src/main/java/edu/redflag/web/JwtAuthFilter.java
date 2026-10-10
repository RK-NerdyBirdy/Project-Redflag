package edu.redflag.web;

import edu.redflag.auth.UserSession;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class JwtAuthFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/login") || path.startsWith("/ws/")) {
            chain.doFilter(req, res);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            String email = UserSession.verifyToken(token);
            if (email != null) {
                request.setAttribute("user", email);
                chain.doFilter(req, res);
                return;
            }
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write("Unauthorized");
    }
}
