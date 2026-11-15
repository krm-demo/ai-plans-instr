package org.krmdemo.plansinstr.lambda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication(scanBasePackages = "org.krmdemo.plansinstr")
public class PlansLambdaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlansLambdaApplication.class, args);
    }

    /** UTC clock used by the repository for createdAt / updatedAt. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
