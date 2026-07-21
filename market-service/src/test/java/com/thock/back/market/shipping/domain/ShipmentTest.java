package com.thock.back.market.shipping.domain;

import com.thock.back.global.exception.CustomException;
import com.thock.back.market.domain.MarketMember;
import com.thock.back.market.domain.Order;
import com.thock.back.market.domain.OrderItem;
import com.thock.back.market.domain.OrderItemState;
import com.thock.back.market.domain.OrderState;
import com.thock.back.market.domain.ShippingAddress;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentTest {
    @Test
    void shipmentStatusChangesUpdateOrderItemAndOrder() {
        Order order = orderWithPreparingItem();
        OrderItem item = order.getItems().get(0);
        Shipment shipment = new Shipment(item, "CJ대한통운", "1234567890");

        shipment.changeStatus(ShippingStatus.SHIPPED);
        assertThat(item.getState()).isEqualTo(OrderItemState.SHIPPING);
        assertThat(order.getState()).isEqualTo(OrderState.SHIPPING);

        shipment.changeStatus(ShippingStatus.IN_TRANSIT);
        shipment.changeStatus(ShippingStatus.DELIVERED);

        assertThat(shipment.getStatus()).isEqualTo(ShippingStatus.DELIVERED);
        assertThat(item.getState()).isEqualTo(OrderItemState.DELIVERED);
        assertThat(order.getState()).isEqualTo(OrderState.DELIVERED);
    }

    @Test
    void deliveredCannotBeSkippedFromPreparing() {
        Order order = orderWithPreparingItem();
        Shipment shipment = new Shipment(order.getItems().get(0), "CJ대한통운", "1234567890");

        assertThatThrownBy(() -> shipment.changeStatus(ShippingStatus.DELIVERED))
                .isInstanceOf(CustomException.class);
    }

    private Order orderWithPreparingItem() {
        MarketMember buyer = new MarketMember(
                "buyer@test.com", "구매자", MemberRole.USER, MemberState.ACTIVE,
                1L, LocalDateTime.now(), LocalDateTime.now());
        Order order = new Order(buyer, new ShippingAddress("06234", "서울시 강남구", "101호"));
        OrderItem item = order.addItem(2L, 100L, "상품", "image", 10_000L, 9_000L, 1);
        item.completePayment();
        item.startPreparing();
        order.updateStateFromItems();
        return order;
    }
}
