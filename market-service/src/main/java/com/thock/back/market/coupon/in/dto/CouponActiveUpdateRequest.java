package com.thock.back.market.coupon.in.dto;

import jakarta.validation.constraints.NotNull;

public record CouponActiveUpdateRequest(@NotNull Boolean active) {}
