package com.thock.back.market.coupon.in.dto;

import com.thock.back.market.coupon.domain.Coupon;
import com.thock.back.market.coupon.domain.CouponDiscountType;

import java.time.LocalDateTime;

public record CouponResponse(Long id, String code, String name, CouponDiscountType discountType,
                             Long discountValue, Long minimumOrderAmount, Long maximumDiscountAmount,
                             Integer totalQuantity, Integer issuedQuantity, LocalDateTime startsAt,
                             LocalDateTime expiresAt) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(coupon.getId(), coupon.getCode(), coupon.getName(), coupon.getDiscountType(),
                coupon.getDiscountValue(), coupon.getMinimumOrderAmount(), coupon.getMaximumDiscountAmount(),
                coupon.getTotalQuantity(), coupon.getIssuedQuantity(), coupon.getStartsAt(), coupon.getExpiresAt());
    }
}
