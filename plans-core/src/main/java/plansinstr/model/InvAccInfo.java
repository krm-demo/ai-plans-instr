package plansinstr.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Investment account that receives the plan's contributions.
 *
 * @param invAccNum investment account number
 * @param occdCode  OCCD code of the investment account
 */
public record InvAccInfo(
        @NotBlank String invAccNum,
        @NotBlank String occdCode) {
}
