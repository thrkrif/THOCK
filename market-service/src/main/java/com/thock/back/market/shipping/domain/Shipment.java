package com.thock.back.market.shipping.domain;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.global.jpa.entity.BaseIdAndTime;
import com.thock.back.market.domain.OrderItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "market_shipments")
@Getter
@NoArgsConstructor
public class Shipment extends BaseIdAndTime {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, unique = true)
    private OrderItem orderItem;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 40)
    private String carrier;

    @Column(nullable = false, length = 100)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShippingStatus status;

    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;

    @Version
    private Long version;

    public Shipment(OrderItem orderItem, String carrier, String trackingNumber) {
        if (orderItem == null || carrier == null || carrier.isBlank()
                || trackingNumber == null || trackingNumber.isBlank()) {
            throw new IllegalArgumentException("Shipment information is required");
        }
        this.orderItem = orderItem;
        this.sellerId = orderItem.getSellerId();
        this.carrier = carrier.trim();
        this.trackingNumber = trackingNumber.trim();
        this.status = ShippingStatus.PREPARING;
    }

    public void changeStatus(ShippingStatus nextStatus) {
        if (nextStatus == null || nextStatus == status) {
            return;
        }
        switch (nextStatus) {
            case SHIPPED -> {
                if (status != ShippingStatus.PREPARING) {
                    throw new CustomException(ErrorCode.SHIPPING_INVALID_STATUS);
                }
                orderItem.startShipping();
                shippedAt = LocalDateTime.now();
            }
            case IN_TRANSIT -> {
                if (status != ShippingStatus.SHIPPED) {
                    throw new CustomException(ErrorCode.SHIPPING_INVALID_STATUS);
                }
            }
            case DELIVERED -> {
                if (status != ShippingStatus.SHIPPED && status != ShippingStatus.IN_TRANSIT) {
                    throw new CustomException(ErrorCode.SHIPPING_INVALID_STATUS);
                }
                orderItem.completeDelivery();
                deliveredAt = LocalDateTime.now();
            }
            case PREPARING -> throw new CustomException(ErrorCode.SHIPPING_INVALID_STATUS);
        }
        status = nextStatus;
        orderItem.getOrder().updateStateFromItems();
    }

    public void updateTracking(String carrier, String trackingNumber) {
        if (status != ShippingStatus.PREPARING) {
            throw new CustomException(ErrorCode.SHIPPING_INVALID_STATUS);
        }
        if (carrier == null || carrier.isBlank() || trackingNumber == null || trackingNumber.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        this.carrier = carrier.trim();
        this.trackingNumber = trackingNumber.trim();
    }
}
