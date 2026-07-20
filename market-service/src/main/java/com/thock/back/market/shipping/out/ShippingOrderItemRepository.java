package com.thock.back.market.shipping.out;

import com.thock.back.market.domain.OrderItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShippingOrderItemRepository extends JpaRepository<OrderItem, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select oi from OrderItem oi join fetch oi.order o where oi.id = :id")
    Optional<OrderItem> findByIdForUpdate(@Param("id") Long id);
}
