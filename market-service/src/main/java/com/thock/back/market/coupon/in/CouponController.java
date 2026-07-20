package com.thock.back.market.coupon.in;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.global.security.AuthUser;
import com.thock.back.global.security.AuthenticatedUser;
import com.thock.back.market.coupon.app.CouponService;
import com.thock.back.market.coupon.in.dto.CouponCreateRequest;
import com.thock.back.market.coupon.in.dto.CouponActiveUpdateRequest;
import com.thock.back.market.coupon.in.dto.CouponResponse;
import com.thock.back.market.coupon.in.dto.MemberCouponResponse;
import com.thock.back.shared.member.domain.MemberRole;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupons")
@Tag(name = "coupon-controller", description = "쿠폰 발급 및 보유 쿠폰 API")
public class CouponController {
    private final CouponService couponService;

    @PostMapping
    public ResponseEntity<CouponResponse> create(@AuthUser AuthenticatedUser user,
                                                  @Valid @RequestBody CouponCreateRequest request) {
        if (user.role() != MemberRole.ADMIN) {
            throw new CustomException(ErrorCode.COUPON_ADMIN_REQUIRED);
        }
        return ResponseEntity.ok(couponService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<CouponResponse>> findIssuableCoupons() {
        return ResponseEntity.ok(couponService.findIssuableCoupons());
    }

    @GetMapping("/admin")
    public ResponseEntity<List<CouponResponse>> findAllForAdmin(@AuthUser AuthenticatedUser user) {
        requireAdmin(user);
        return ResponseEntity.ok(couponService.findAllForAdmin());
    }

    @PatchMapping("/{couponId}/active")
    public ResponseEntity<CouponResponse> updateActive(@AuthUser AuthenticatedUser user,
                                                       @PathVariable Long couponId,
                                                       @Valid @RequestBody CouponActiveUpdateRequest request) {
        requireAdmin(user);
        return ResponseEntity.ok(couponService.updateActive(couponId, request));
    }

    @PostMapping("/{couponId}/issue")
    public ResponseEntity<MemberCouponResponse> issue(@AuthUser AuthenticatedUser user,
                                                      @PathVariable Long couponId) {
        return ResponseEntity.ok(couponService.issue(user.memberId(), couponId));
    }

    @GetMapping("/me")
    public ResponseEntity<List<MemberCouponResponse>> findMyCoupons(@AuthUser AuthenticatedUser user) {
        return ResponseEntity.ok(couponService.findMyCoupons(user.memberId()));
    }

    private void requireAdmin(AuthenticatedUser user) {
        if (user.role() != MemberRole.ADMIN) {
            throw new CustomException(ErrorCode.COUPON_ADMIN_REQUIRED);
        }
    }
}
