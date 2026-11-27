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

    // [M4] 응답(ShipmentResponse)이 주문상품/주문을 같이 쓰므로 함께 읽는다.
    // 지연 로딩이면 batch fetch(100건 단위)로 주문상품·주문 조회가 배송 건수에 비례해 추가로 나간다.
    @Query("select s from Shipment s join fetch s.orderItem oi join fetch oi.order o where s.sellerId = :sellerId order by s.createdAt desc")
    List<Shipment> findBySellerIdOrderByCreatedAtDesc(@Param("sellerId") Long sellerId);

    List<Shipment> findAllByOrderByCreatedAtDesc();
}
