package com.thock.back.market.coupon.domain;

import com.thock.back.global.exception.CustomException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {
    @Test
    void percentageCouponRespectsMaximumDiscount() {
        Coupon coupon = new Coupon("WELCOME", "신규 가입", CouponDiscountType.PERCENTAGE,
                20L, 10_000L, 5_000L, 100,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.calculateDiscount(50_000L)).isEqualTo(5_000L);
    }

    @Test
    void fixedCouponCannotBeAppliedBelowMinimumOrderAmount() {
        Coupon coupon = new Coupon("FIXED", "정액 할인", CouponDiscountType.FIXED,
                3_000L, 10_000L, null, 100,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThatThrownBy(() -> coupon.calculateDiscount(9_999L))
                .isInstanceOf(CustomException.class);
    }
}
