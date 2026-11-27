package com.thock.back.payment.domain;


import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.global.jpa.entity.BaseIdAndTime;
import com.thock.back.payment.out.event.PaymentAddPaymentLogEvent;
import com.thock.back.shared.payment.dto.PaymentDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@NoArgsConstructor
// [H2] 결제 확정/환불/취소 경로의 findByOrderId 용. payment 는 Flyway 가 없고 ddl-auto=update 라
// 기동 시 Hibernate 가 이 인덱스를 만든다. (UNIQUE 는 기존 중복 주문번호 데이터 정리 후 검토)
@Table(name = "payment_payments",
        indexes = @Index(name = "idx_payment_order_id", columnList = "order_id"))
@Slf4j
public class Payment extends BaseIdAndTime {
    private Long amount;

    private String orderId;

    private String paymentKey;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @ManyToOne(fetch = LAZY)
    private PaymentMember buyer;

    private Long pgAmount;

    private Long refundedAmount;

    @Version   // 낙관적 락 추가
    private Long version;

    public Payment(Long pgAmount, PaymentMember buyer, PaymentStatus status, String orderId, Long amount, String paymentKey) {
        this.pgAmount = pgAmount;
        this.buyer = buyer;
        this.status = status;
        this.orderId = orderId;
        this.amount = amount;
        this.refundedAmount = 0L;
        this.paymentKey = paymentKey;
    }

    public PaymentDto toDto(){
        return new PaymentDto(
                getId(),
                getOrderId(),
                getPaymentKey(),
                getBuyer().getId(),
                getPgAmount(),
                getAmount(),
                getCreatedAt(),
                getRefundedAmount()
        );
    }
    public void createPaymentLogEvent(){
        publishEvent(
                new PaymentAddPaymentLogEvent(
                        toDto()
                )
        );
    }

    public void updatePaymentStatus(PaymentStatus status){
        if (this.status == status) {
            return;
        }
        this.status = status;
    }

    public void updatePaymentKey(String paymentKey){
        this.paymentKey = paymentKey;
    }

    public boolean updatePaymentRefundedAmount(Long amount){
        if (this.refundedAmount+amount>this.amount){
            throw new CustomException(ErrorCode.INVALID_REFUND_AMOUNT);
        }
        this.refundedAmount += amount;
        return true;
    }
}
