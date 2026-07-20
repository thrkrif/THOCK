package com.thock.back.market.shipping.in.dto;

import com.thock.back.market.shipping.domain.ShippingStatus;
import jakarta.validation.constraints.NotNull;

public record ShipmentStatusUpdateRequest(@NotNull ShippingStatus status) {}
