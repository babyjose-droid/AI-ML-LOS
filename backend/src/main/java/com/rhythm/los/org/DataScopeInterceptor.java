package com.rhythm.los.org;

import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Every /api/applications/{id}/... call outside the user's branch scope answers 404, as if the application did not exist. */
@Configuration
public class DataScopeInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private static final Pattern APP = Pattern.compile("^/api/applications/(\\d+)(/.*)?$");
    private final DataScope scope;
    private final LoanApplicationRepository apps;

    public DataScopeInterceptor(DataScope scope, LoanApplicationRepository apps) {
        this.scope = scope;
        this.apps = apps;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/applications/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        Matcher m = APP.matcher(req.getRequestURI());
        if (!m.matches()) return true;
        CurrentUser u = CurrentUser.get();
        apps.findById(Long.valueOf(m.group(1))).ifPresent(a -> {
            if (!scope.canSee(u, a)) throw ApiException.notFound("Application " + m.group(1));
        });
        return true;
    }
}
