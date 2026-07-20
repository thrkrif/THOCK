package com.thock.back.market.shipping.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.market.domain.OrderItem;
import com.thock.back.market.shipping.domain.Shipment;
import com.thock.back.market.shipping.domain.ShippingStatus;
import com.thock.back.market.shipping.in.dto.ShipmentCreateRequest;
import com.thock.back.market.shipping.in.dto.ShipmentResponse;
import com.thock.back.market.shipping.in.dto.ShipmentTrackingUpdateRequest;
import com.thock.back.market.shipping.out.ShippingOrderItemRepository;
import com.thock.back.market.shipping.out.ShipmentRepository;
import com.thock.back.shared.member.domain.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShippingService {
    private final ShipmentRepository shipmentRepository;
    private final ShippingOrderItemRepository orderItemRepository;

    @Transactional
    public ShipmentResponse create(Long memberId, MemberRole role, ShipmentCreateRequest request) {
        requireSellerOrAdmin(role);
        OrderItem item = orderItemRepository.findByIdForUpdate(request.orderItemId())
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        if (role != MemberRole.ADMIN && !memberId.equals(item.getSellerId())) {
            throw new CustomException(ErrorCode.SHIPPING_SELLER_FORBIDDEN);
        }
        if (shipmentRepository.existsByOrderItemId(item.getId())) {
            throw new CustomException(ErrorCode.SHIPPING_INVALID_STATUS);
        }
        try {
            item.startPreparing();
        } catch (CustomException e) {
            throw new CustomException(ErrorCode.SHIPPING_ORDER_ITEM_NOT_READY, e);
        }
        item.getOrder().updateStateFromItems();
        return ShipmentResponse.from(shipmentRepository.save(new Shipment(item, request.carrier(), request.trackingNumber())));
    }

    @Transactional
    public ShipmentResponse changeStatus(Long memberId, MemberRole role, Long shipmentId, ShippingStatus status) {
        requireSellerOrAdmin(role);
        Shipment shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHIPPING_NOT_FOUND));
        if (role != MemberRole.ADMIN && !memberId.equals(shipment.getSellerId())) {
            throw new CustomException(ErrorCode.SHIPPING_SELLER_FORBIDDEN);
        }
        shipment.changeStatus(status);
        return ShipmentResponse.from(shipment);
    }

    @Transactional
    public ShipmentResponse updateTracking(Long memberId, MemberRole role, Long shipmentId,
                                           ShipmentTrackingUpdateRequest request) {
        requireSellerOrAdmin(role);
        Shipment shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHIPPING_NOT_FOUND));
        if (role != MemberRole.ADMIN && !memberId.equals(shipment.getSellerId())) {
            throw new CustomException(ErrorCode.SHIPPING_SELLER_FORBIDDEN);
        }
        shipment.updateTracking(request.carrier(), request.trackingNumber());
        return ShipmentResponse.from(shipment);
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> findMine(Long memberId, MemberRole role) {
        List<Shipment> shipments = role == MemberRole.ADMIN
                ? shipmentRepository.findAllByOrderByCreatedAtDesc()
                : role == MemberRole.SELLER
                ? shipmentRepository.findBySellerIdOrderByCreatedAtDesc(memberId)
                : shipmentRepository.findByBuyerId(memberId);
        return shipments.stream().map(ShipmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ShipmentResponse findOne(Long memberId, MemberRole role, Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHIPPING_NOT_FOUND));
        boolean allowed = role == MemberRole.ADMIN
                || memberId.equals(shipment.getSellerId())
                || memberId.equals(shipment.getOrderItem().getOrder().getBuyer().getId());
        if (!allowed) {
            throw new CustomException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        return ShipmentResponse.from(shipment);
    }

    private void requireSellerOrAdmin(MemberRole role) {
        if (role != MemberRole.SELLER && role != MemberRole.ADMIN) {
            throw new CustomException(ErrorCode.SHIPPING_SELLER_FORBIDDEN);
        }
    }
}
