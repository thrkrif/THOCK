package com.thock.back.market.coupon.domain;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "market_member_coupons", uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_coupon", columnNames = {"member_id", "coupon_id"})
})
@Getter
@NoArgsConstructor
public class MemberCoupon extends BaseIdAndTime {
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberCouponStatus status;

    private LocalDateTime usedAt;
    private String usedOrderNumber;

    @Version
    private Long version;

    public MemberCoupon(Long memberId, Coupon coupon) {
        this.memberId = memberId;
        this.coupon = coupon;
        this.status = MemberCouponStatus.AVAILABLE;
    }

    public void use(String orderNumber) {
        if (status != MemberCouponStatus.AVAILABLE) {
            throw new CustomException(ErrorCode.COUPON_ALREADY_USED);
        }
        status = MemberCouponStatus.USED;
        usedAt = LocalDateTime.now();
        usedOrderNumber = orderNumber;
    }

    public void restore(String orderNumber) {
        if (status == MemberCouponStatus.USED && orderNumber.equals(usedOrderNumber)) {
            status = MemberCouponStatus.AVAILABLE;
            usedAt = null;
            usedOrderNumber = null;
        }
    }
}
