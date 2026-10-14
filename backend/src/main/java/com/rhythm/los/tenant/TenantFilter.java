package com.rhythm.los.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets the tenant for the request: from the signed token when there is one (the token decides; a different
 * X-Tenant header is refused), otherwise from the X-Tenant header (sign-in, borrower OTP, video KYC link), otherwise
 * the default tenant. Unknown or suspended tenants are refused.
 */
public class TenantFilter extends OncePerRequestFilter {
    private final TenantRegistry registry;

    public TenantFilter(TenantRegistry registry) { this.registry = registry; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("X-Tenant");
        header = header == null || header.isBlank() ? null : header.trim().toLowerCase();
        String code = header;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString("tenant");
            claim = claim == null ? "default" : claim;
            if (header != null && !header.equals(claim)) { refuse(res, 403, "TENANT_MISMATCH", "This session belongs to another organisation"); return; }
            code = claim;
        }
        if (code == null) code = "default";
        var entry = registry.find(code).orElse(null);
        if (entry == null) { refuse(res, 404, "UNKNOWN_TENANT", "Unknown organisation code"); return; }
        if ("SUSPENDED".equals(entry.status()) && req.getRequestURI().startsWith("/api/")) { refuse(res, 403, "TENANT_SUSPENDED", "This organisation is suspended"); return; }
        TenantContext.set(entry.tenant());
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    static void refuse(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
