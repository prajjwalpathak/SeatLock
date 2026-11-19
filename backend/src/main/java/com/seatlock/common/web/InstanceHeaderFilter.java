package com.seatlock.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code X-SeatLock-Instance: app-1}: which instance answered, so requests through the load balancer
 * can be told apart. The id is a configured name ({@code SEATLOCK_INSTANCE_ID}), never a host name or IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after RequestIdFilter's position; set before any response is written
public class InstanceHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-SeatLock-Instance";

    private final String instanceId;

    public InstanceHeaderFilter(@Value("${seatlock.instance-id:local}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instanceId);
        chain.doFilter(request, response);
    }
}
