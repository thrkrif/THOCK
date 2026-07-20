package com.thock.back.market.coupon.out;

import com.thock.back.market.coupon.domain.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

    List<Coupon> findByActiveTrueAndStartsAtLessThanEqualAndExpiresAtAfterOrderByExpiresAtAsc(
            LocalDateTime startsAt, LocalDateTime expiresAt);
}
