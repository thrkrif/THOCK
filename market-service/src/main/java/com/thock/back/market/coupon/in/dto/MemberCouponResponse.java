package com.thock.back.market.coupon.in.dto;

import com.thock.back.market.coupon.domain.MemberCoupon;
import com.thock.back.market.coupon.domain.MemberCouponStatus;
import com.thock.back.market.coupon.domain.CouponDiscountType;

import java.time.LocalDateTime;

public record MemberCouponResponse(Long id, Long couponId, String code, String name,
                                   MemberCouponStatus status, CouponDiscountType discountType,
                                   Long discountValue, Long minimumOrderAmount,
                                   LocalDateTime expiresAt, String usedOrderNumber) {
    public static MemberCouponResponse from(MemberCoupon memberCoupon) {
        var coupon = memberCoupon.getCoupon();
        return new MemberCouponResponse(memberCoupon.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                memberCoupon.getStatus(), coupon.getDiscountType(), coupon.getDiscountValue(),
                coupon.getMinimumOrderAmount(), coupon.getExpiresAt(), memberCoupon.getUsedOrderNumber());
    }
}
