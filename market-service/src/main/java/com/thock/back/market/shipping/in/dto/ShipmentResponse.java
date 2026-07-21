package com.thock.back.market.shipping.in.dto;

import com.thock.back.market.shipping.domain.Shipment;
import com.thock.back.market.shipping.domain.ShippingStatus;

import java.time.LocalDateTime;

public record ShipmentResponse(Long shipmentId, Long orderId, String orderNumber, Long orderItemId,
                               Long productId, String productName, Long sellerId, String carrier,
                               String trackingNumber, ShippingStatus status, LocalDateTime shippedAt,
                               LocalDateTime deliveredAt) {
    public static ShipmentResponse from(Shipment shipment) {
        var item = shipment.getOrderItem();
        var order = item.getOrder();
        return new ShipmentResponse(shipment.getId(), order.getId(), order.getOrderNumber(), item.getId(),
                item.getProductId(), item.getProductName(), shipment.getSellerId(), shipment.getCarrier(),
                shipment.getTrackingNumber(), shipment.getStatus(), shipment.getShippedAt(), shipment.getDeliveredAt());
    }
}
