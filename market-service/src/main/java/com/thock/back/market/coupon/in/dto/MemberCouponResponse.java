package com.thock.back.market.coupon.in.dto;

import com.thock.back.market.coupon.domain.MemberCoupon;
import com.thock.back.market.coupon.domain.MemberCouponStatus;

import java.time.LocalDateTime;

public record MemberCouponResponse(Long id, Long couponId, String code, String name,
                                   MemberCouponStatus status, Long discountValue,
                                   LocalDateTime expiresAt, String usedOrderNumber) {
    public static MemberCouponResponse from(MemberCoupon memberCoupon) {
        var coupon = memberCoupon.getCoupon();
        return new MemberCouponResponse(memberCoupon.getId(), coupon.getId(), coupon.getCode(), coupon.getName(),
                memberCoupon.getStatus(), coupon.getDiscountValue(), coupon.getExpiresAt(), memberCoupon.getUsedOrderNumber());
    }
}
