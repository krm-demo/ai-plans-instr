package com.example.plans.lambda;

import com.example.plans.model.RuntimeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

/** Describes this running Lambda instance; copied into restInfo on every save. */
@Configuration
@PropertySource("classpath:build.properties")
public class RuntimeInfoConfig {

    @Bean
    public RuntimeInfo runtimeInfo(@Value("${build.number:unknown}") String buildNumber) {
        return new RuntimeInfo(
                fullHostName(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
                buildNumber);
    }

    private static String fullHostName() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            String host = System.getenv("HOSTNAME");
            return host != null ? host : "unknown";
        }
    }
}
