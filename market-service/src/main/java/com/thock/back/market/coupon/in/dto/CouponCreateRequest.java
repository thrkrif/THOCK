package com.thock.back.market.coupon.in.dto;

import com.thock.back.market.coupon.domain.CouponDiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

public record CouponCreateRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull CouponDiscountType discountType,
        @NotNull @Positive Long discountValue,
        @NotNull @PositiveOrZero Long minimumOrderAmount,
        @Positive Long maximumDiscountAmount,
        @NotNull @Positive Integer totalQuantity,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime expiresAt
) {}
