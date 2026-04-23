package com.kafkaasr.control.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TenantPolicyRollbackRequest(
        @Min(value = 1, message = "targetVersion must be >= 1")
        Long targetVersion,

        List<@NotBlank(message = "distributionRegions must not contain blank values") String> distributionRegions) {
}
