package com.example.ssmdemo.domain.order.entity;

import com.example.ssmdemo.domain.order.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 엔티티
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @Column(length = 26)
    private String id;

    @Column(nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(length = 100)
    private String customerEmail;

    @Column(length = 50)
    private String paymentMethod;

    private String paymentId;

    private LocalDateTime paidAt;

    private LocalDateTime shippedAt;

    private LocalDateTime deliveredAt;

    private LocalDateTime cancelledAt;

    private LocalDateTime refundedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime modifiedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = OrderStatus.CREATED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedAt = LocalDateTime.now();
    }

    // === 상태 변경 메서드 ===

    public void markAsPaid(String paymentId) {
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = LocalDateTime.now();
    }

    public void markAsShipped() {
        this.status = OrderStatus.SHIPPED;
        this.shippedAt = LocalDateTime.now();
    }

    public void markAsDelivered() {
        this.status = OrderStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    public void markAsCancelled() {
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public void markAsReturned() {
        this.status = OrderStatus.RETURNED;
        this.refundedAt = LocalDateTime.now();
    }

    public void updateStatus(OrderStatus status) {
        this.status = status;
    }
}
