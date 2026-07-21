package com.thock.back.market.coupon.domain;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "market_coupons")
@Getter
@NoArgsConstructor
public class Coupon extends BaseIdAndTime {
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponDiscountType discountType;

    @Column(nullable = false)
    private Long discountValue;

    @Column(nullable = false)
    private Long minimumOrderAmount;

    private Long maximumDiscountAmount;

    @Column(nullable = false)
    private Integer totalQuantity;

    @Column(nullable = false)
    private Integer issuedQuantity;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    public Coupon(String code, String name, CouponDiscountType discountType, Long discountValue,
                  Long minimumOrderAmount, Long maximumDiscountAmount, Integer totalQuantity,
                  LocalDateTime startsAt, LocalDateTime expiresAt) {
        if (code == null || code.isBlank() || name == null || name.isBlank()
                || discountType == null || discountValue == null || discountValue <= 0
                || minimumOrderAmount == null || minimumOrderAmount < 0
                || totalQuantity == null || totalQuantity <= 0
                || startsAt == null || expiresAt == null || !expiresAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Invalid coupon definition");
        }
        if (discountType == CouponDiscountType.PERCENTAGE && discountValue > 100) {
            throw new IllegalArgumentException("Percentage discount must be between 1 and 100");
        }
        this.code = code.trim();
        this.name = name.trim();
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minimumOrderAmount = minimumOrderAmount;
        this.maximumDiscountAmount = maximumDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public void issue() {
        if (!active || issuedQuantity >= totalQuantity) {
            throw new CustomException(ErrorCode.COUPON_ISSUE_SOLD_OUT);
        }
        issuedQuantity++;
    }

    public boolean isIssuableAt(LocalDateTime now) {
        return isUsableAt(now) && issuedQuantity < totalQuantity;
    }

    public boolean isUsableAt(LocalDateTime now) {
        return active && !now.isBefore(startsAt) && now.isBefore(expiresAt);
    }

    public void updateActive(boolean active) {
        this.active = active;
    }

    public long calculateDiscount(long orderAmount) {
        if (orderAmount < minimumOrderAmount) {
            throw new CustomException(ErrorCode.COUPON_NOT_APPLICABLE);
        }
        long discount = discountType == CouponDiscountType.FIXED
                ? discountValue
                : orderAmount * discountValue / 100;
        if (maximumDiscountAmount != null) {
            discount = Math.min(discount, maximumDiscountAmount);
        }
        return Math.min(discount, orderAmount);
    }
}
