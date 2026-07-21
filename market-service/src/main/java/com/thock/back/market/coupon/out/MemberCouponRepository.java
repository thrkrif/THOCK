package com.thock.back.market.coupon.out;

import com.thock.back.market.coupon.domain.MemberCoupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {
    boolean existsByMemberIdAndCouponId(Long memberId, Long couponId);

    List<MemberCoupon> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mc from MemberCoupon mc join fetch mc.coupon where mc.memberId = :memberId and mc.coupon.id = :couponId")
    Optional<MemberCoupon> findByMemberIdAndCouponIdForUpdate(@Param("memberId") Long memberId,
                                                               @Param("couponId") Long couponId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mc from MemberCoupon mc join fetch mc.coupon where mc.memberId = :memberId and mc.coupon.id = :couponId and mc.usedOrderNumber = :orderNumber")
    Optional<MemberCoupon> findUsedByOrderForUpdate(@Param("memberId") Long memberId,
                                                     @Param("couponId") Long couponId,
                                                     @Param("orderNumber") String orderNumber);
}
