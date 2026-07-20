package com.thock.back.market.shipping.in;

import com.thock.back.global.security.AuthUser;
import com.thock.back.global.security.AuthenticatedUser;
import com.thock.back.market.shipping.app.ShippingService;
import com.thock.back.market.shipping.in.dto.ShipmentCreateRequest;
import com.thock.back.market.shipping.in.dto.ShipmentResponse;
import com.thock.back.market.shipping.in.dto.ShipmentStatusUpdateRequest;
import com.thock.back.market.shipping.in.dto.ShipmentTrackingUpdateRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/shipments")
@Tag(name = "shipping-controller", description = "배송 생성 및 추적 API")
public class ShippingController {
    private final ShippingService shippingService;

    @PostMapping
    public ResponseEntity<ShipmentResponse> create(@AuthUser AuthenticatedUser user,
                                                   @Valid @RequestBody ShipmentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shippingService.create(user.memberId(), user.role(), request));
    }

    @GetMapping("/me")
    public ResponseEntity<List<ShipmentResponse>> findMine(@AuthUser AuthenticatedUser user) {
        return ResponseEntity.ok(shippingService.findMine(user.memberId(), user.role()));
    }

    @GetMapping("/{shipmentId}")
    public ResponseEntity<ShipmentResponse> findOne(@AuthUser AuthenticatedUser user,
                                                    @PathVariable Long shipmentId) {
        return ResponseEntity.ok(shippingService.findOne(user.memberId(), user.role(), shipmentId));
    }

    @PatchMapping("/{shipmentId}/status")
    public ResponseEntity<ShipmentResponse> changeStatus(@AuthUser AuthenticatedUser user,
                                                          @PathVariable Long shipmentId,
                                                          @Valid @RequestBody ShipmentStatusUpdateRequest request) {
        return ResponseEntity.ok(shippingService.changeStatus(
                user.memberId(), user.role(), shipmentId, request.status()));
    }

    @PatchMapping("/{shipmentId}/tracking")
    public ResponseEntity<ShipmentResponse> updateTracking(@AuthUser AuthenticatedUser user,
                                                            @PathVariable Long shipmentId,
                                                            @Valid @RequestBody ShipmentTrackingUpdateRequest request) {
        return ResponseEntity.ok(shippingService.updateTracking(
                user.memberId(), user.role(), shipmentId, request));
    }
}
