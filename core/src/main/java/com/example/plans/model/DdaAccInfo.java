package com.example.plans.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Demand deposit account (DDA) that funds the plan.
 *
 * @param ddaAccNum DDA account number
 * @param ddaBankId identifier of the bank that holds the DDA
 */
public record DdaAccInfo(
        @NotBlank String ddaAccNum,
        @NotBlank String ddaBankId) {
}
