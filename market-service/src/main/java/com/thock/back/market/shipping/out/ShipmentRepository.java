package com.thock.back.market.shipping.out;

import com.thock.back.market.shipping.domain.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    boolean existsByOrderItemId(Long orderItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s join fetch s.orderItem oi join fetch oi.order o where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") Long id);

    @Query("select s from Shipment s join fetch s.orderItem oi join fetch oi.order o where o.buyer.id = :memberId order by s.createdAt desc")
    List<Shipment> findByBuyerId(@Param("memberId") Long memberId);

    List<Shipment> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    List<Shipment> findAllByOrderByCreatedAtDesc();
}
