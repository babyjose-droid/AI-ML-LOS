package com.rhythm.los.security;

import com.rhythm.los.org.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The signed-in user, taken from the JWT. "system" is used for automated steps. */
public record CurrentUser(String username, Role role, String name, Long branchId) {

    public static final CurrentUser SYSTEM = new CurrentUser("system", Role.ADMIN, "Rhythm automation", null);

    public static CurrentUser get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Jwt jwt) {
            Object b = jwt.getClaim("branchId");
            Long branch = b == null ? null : Long.valueOf(b.toString());
            return new CurrentUser(jwt.getSubject(), Role.valueOf(jwt.getClaimAsString("role")),
                    jwt.getClaimAsString("name"), branch);
        }
        return SYSTEM;
    }
}
