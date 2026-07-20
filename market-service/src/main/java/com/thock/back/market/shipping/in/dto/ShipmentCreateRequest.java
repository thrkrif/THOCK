package com.thock.back.market.shipping.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ShipmentCreateRequest(
        @NotNull @Positive Long orderItemId,
        @NotBlank String carrier,
        @NotBlank String trackingNumber
) {}
