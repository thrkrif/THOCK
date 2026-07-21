package com.thock.back.market.shipping.in.dto;

import jakarta.validation.constraints.NotBlank;

public record ShipmentTrackingUpdateRequest(@NotBlank String carrier, @NotBlank String trackingNumber) {}
