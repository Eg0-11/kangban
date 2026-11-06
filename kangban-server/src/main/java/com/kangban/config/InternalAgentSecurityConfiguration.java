package com.kangban.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangban.agent.internal.InternalAgentAccessGuard;
import com.kangban.agent.internal.InternalAgentAuthenticationFilter;
import com.kangban.agent.internal.InternalAgentError;
import com.kangban.agent.internal.InternalAgentException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class InternalAgentSecurityConfiguration {
    @Bean
    @Order(1)
    public SecurityFilterChain internalAgentFilterChain(HttpSecurity http, InternalAgentAccessGuard guard,
                                                       ObjectMapper mapper) throws Exception {
        return http.securityMatcher("/internal/agent/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/internal/agent/v1/tools").hasAuthority("AGENT_SERVICE")
                        .requestMatchers(HttpMethod.POST, "/internal/agent/v1/tools/execute",
                                "/internal/agent/v1/rag/search", "/internal/agent/v1/actions/propose").hasAuthority("AGENT_SERVICE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> InternalAgentError.write(response, mapper,
                                new InternalAgentException(401, "INVALID_SERVICE_CREDENTIAL", "内部 Agent 服务身份无效。", false), null))
                        .accessDeniedHandler((request, response, exception) -> InternalAgentError.write(response, mapper,
                                new InternalAgentException(403, "ACCESS_DENIED", "内部 Agent 访问被拒绝。", false), null)))
                .addFilterBefore(new InternalAgentAuthenticationFilter(guard, mapper), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
