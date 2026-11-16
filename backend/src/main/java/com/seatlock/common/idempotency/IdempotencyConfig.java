package com.seatlock.common.idempotency;

import java.time.Clock;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires {@link IdempotencyInterceptor} to every {@link Idempotent} method with plain Spring AOP
 * (no AspectJ dependency).
 * <ul>
 *   <li>Infrastructure role: picked up by the auto-proxy creator that method security, caching and
 *       transactions already use.</li>
 *   <li>Lowest precedence = innermost advice, i.e. it runs after {@code @PreAuthorize}.</li>
 *   <li>The advisor is created very early (with the other post-processors), so it looks the interceptor
 *       bean up on first use instead of forcing the store, JSON mapper etc. to be created early.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    @Bean
    IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore store, IdempotencyProperties props,
                                                  JsonMapper json, Clock clock) {
        return new IdempotencyInterceptor(store, props, json, clock);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor idempotencyAdvisor(ObjectProvider<IdempotencyInterceptor> interceptor) {
        MethodInterceptor lazy = invocation -> interceptor.getObject().invoke(invocation);
        var advisor = new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(Idempotent.class), lazy);
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }
}
