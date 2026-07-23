package com.thock.back.product.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductReviewCreateRequest(
        @NotNull
        @DecimalMin("0.5")
        @DecimalMax("5.0")
        @Digits(integer = 1, fraction = 1)
        BigDecimal rating,

        @NotBlank
        @Size(max = 1000)
        String content
) {
}
