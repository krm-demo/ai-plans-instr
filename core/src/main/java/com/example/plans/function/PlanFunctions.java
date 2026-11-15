package com.example.plans.function;

import com.example.plans.api.PlanApi;
import com.example.plans.model.PlanInstructions;
import com.example.plans.model.PlanSearchCriteria;
import com.example.plans.model.RestInfo;
import com.example.plans.model.RuntimeInfo;
import com.example.plans.persistence.PlanJdbcClient;
import com.example.plans.persistence.PlanNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;

import java.time.Clock;
import java.util.Set;
import java.util.function.Function;

/**
 * One function bean per operation. Choose which one a Lambda runs with the environment
 * variable {@code SPRING_CLOUD_FUNCTION_DEFINITION} (e.g. {@code loadPlan}).
 */
@Configuration
public class PlanFunctions {

    @Bean
    public Function<PlanApi.LoadRequest, PlanInstructions> loadPlan(
            PlanJdbcClient plans, Validator validator) {
        return request -> {
            validate(validator, request);
            return plans.load(request.planId())
                    .orElseThrow(() -> new PlanNotFoundException(request.planId()));
        };
    }

    @Bean
    public Function<Message<PlanInstructions>, PlanInstructions> savePlan(
            PlanJdbcClient plans, Validator validator, RuntimeInfo runtimeInfo, Clock clock) {
        return message -> {
            PlanInstructions plan = message.getPayload();
            validate(validator, plan);
            // restInfo is always set here; anything the caller sent for it is replaced.
            RestInfo restInfo = new RestInfo(
                    userAgent(message.getHeaders()), clock.instant(), runtimeInfo);
            return plans.save(plan.withRestInfo(restInfo));
        };
    }

    /** HTTP headers arrive as message headers; API Gateway may use any letter case. */
    private static String userAgent(MessageHeaders headers) {
        return headers.entrySet().stream()
                .filter(e -> "user-agent".equalsIgnoreCase(e.getKey()) && e.getValue() != null)
                .map(e -> e.getValue().toString())
                .findFirst()
                .orElse(null);
    }

    @Bean
    public Function<PlanApi.LookupRequest, PlanInstructions> lookupPlan(
            PlanJdbcClient plans, Validator validator) {
        return request -> {
            validate(validator, request);
            return plans.lookup(request.userId(), request.planType())
                    .orElseThrow(() -> new PlanNotFoundException(
                            "No plan for user " + request.userId() + " and type " + request.planType()));
        };
    }

    @Bean
    public Function<PlanSearchCriteria, PlanApi.SearchResult> searchPlans(PlanJdbcClient plans) {
        return criteria -> new PlanApi.SearchResult(
                plans.search(criteria), criteria.limit(), criteria.offset());
    }

    private static <T> void validate(Validator validator, T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
