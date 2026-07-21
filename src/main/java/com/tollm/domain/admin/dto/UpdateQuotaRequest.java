package com.tollm.domain.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateQuotaRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal monthlyCostLimit
) {
}
