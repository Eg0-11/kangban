package com.kangban.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 只在内部安全链中构造，避免作为全局 Servlet Filter 自动注册。 */
@RequiredArgsConstructor
public final class InternalAgentAuthenticationFilter extends OncePerRequestFilter {
    private final InternalAgentAccessGuard guard;
    private final ObjectMapper mapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        InternalAgentCall call;
        try {
            call = guard.authorize(request.getHeader("X-Agent-Service-Token"),
                    request.getHeader("X-Agent-Context"), request.getHeader("X-Agent-Deadline-Epoch-Ms"));
        } catch (InternalAgentException exception) {
            InternalAgentError.write(response, mapper, exception, null);
            return;
        }
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                call, null, List.of(new SimpleGrantedAuthority("AGENT_SERVICE"))));
        SecurityContextHolder.setContext(securityContext);
        chain.doFilter(request, response);
    }
}
