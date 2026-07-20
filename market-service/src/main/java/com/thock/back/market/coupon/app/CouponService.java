package com.thock.back.market.coupon.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.coupon.domain.Coupon;
import com.thock.back.market.coupon.domain.MemberCoupon;
import com.thock.back.market.coupon.in.dto.CouponCreateRequest;
import com.thock.back.market.coupon.in.dto.CouponResponse;
import com.thock.back.market.coupon.in.dto.CouponActiveUpdateRequest;
import com.thock.back.market.coupon.in.dto.MemberCouponResponse;
import com.thock.back.market.coupon.out.CouponRepository;
import com.thock.back.market.coupon.out.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    @Transactional
    public CouponResponse create(CouponCreateRequest request) {
        return CouponResponse.from(couponRepository.save(new Coupon(
                request.code(), request.name(), request.discountType(), request.discountValue(),
                request.minimumOrderAmount(), request.maximumDiscountAmount(), request.totalQuantity(),
                request.startsAt(), request.expiresAt())));
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> findIssuableCoupons() {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.findByActiveTrueAndStartsAtLessThanEqualAndExpiresAtAfterOrderByExpiresAtAsc(now, now)
                .stream().map(CouponResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> findAllForAdmin() {
        return couponRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(CouponResponse::from).toList();
    }

    @Transactional
    public CouponResponse updateActive(Long couponId, CouponActiveUpdateRequest request) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new CustomException(ErrorCode.COUPON_NOT_FOUND));
        coupon.updateActive(request.active());
        return CouponResponse.from(coupon);
    }

    @Transactional
    public MemberCouponResponse issue(Long memberId, Long couponId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new CustomException(ErrorCode.COUPON_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        if (!coupon.isIssuableAt(now)) {
            throw new CustomException(ErrorCode.COUPON_NOT_AVAILABLE);
        }
        if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new CustomException(ErrorCode.COUPON_ALREADY_ISSUED);
        }
        coupon.issue();
        try {
            return MemberCouponResponse.from(memberCouponRepository.save(new MemberCoupon(memberId, coupon)));
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.COUPON_ALREADY_ISSUED, e);
        }
    }

    @Transactional(readOnly = true)
    public List<MemberCouponResponse> findMyCoupons(Long memberId) {
        return memberCouponRepository.findByMemberIdOrderByCreatedAtDesc(memberId)
                .stream().map(MemberCouponResponse::from).toList();
    }

    /** 주문 트랜잭션 안에서 쿠폰을 사용한다. 쿠폰/보유 쿠폰을 모두 잠가 중복 사용을 막는다. */
    @Transactional
    public long apply(Long memberId, Long couponId, long orderAmount, String orderNumber) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new CustomException(ErrorCode.COUPON_NOT_FOUND));
        MemberCoupon memberCoupon = memberCouponRepository.findByMemberIdAndCouponIdForUpdate(memberId, couponId)
                .orElseThrow(() -> new CustomException(ErrorCode.COUPON_NOT_OWNED));
        if (!coupon.isUsableAt(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.COUPON_NOT_AVAILABLE);
        }
        long discount = coupon.calculateDiscount(orderAmount);
        memberCoupon.use(orderNumber);
        return discount;
    }

    @Transactional
    public void restore(Long memberId, Long couponId, String orderNumber) {
        if (couponId == null) {
            return;
        }
        memberCouponRepository.findUsedByOrderForUpdate(memberId, couponId, orderNumber)
                .ifPresent(memberCoupon -> memberCoupon.restore(orderNumber));
    }
}
