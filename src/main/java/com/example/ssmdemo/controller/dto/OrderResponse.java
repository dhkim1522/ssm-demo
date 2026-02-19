package com.example.ssmdemo.controller.dto;

import com.example.ssmdemo.domain.order.entity.Order;

import java.math.BigDecimal;

public record OrderResponse(
    String id,
    String productId,
    Integer quantity,
    BigDecimal totalAmount,
    String status,
    String statusDescription,
    String customerEmail,
    String paymentMethod,
    String paymentId,
    String paidAt,
    String shippedAt,
    String deliveredAt,
    String cancelledAt,
    String createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
            order.getId(),
            order.getProductId(),
            order.getQuantity(),
            order.getTotalAmount(),
            order.getStatus().name(),
            order.getStatus().getDescription(),
            order.getCustomerEmail(),
            order.getPaymentMethod(),
            order.getPaymentId(),
            order.getPaidAt() != null ? order.getPaidAt().toString() : null,
            order.getShippedAt() != null ? order.getShippedAt().toString() : null,
            order.getDeliveredAt() != null ? order.getDeliveredAt().toString() : null,
            order.getCancelledAt() != null ? order.getCancelledAt().toString() : null,
            order.getCreatedAt() != null ? order.getCreatedAt().toString() : null
        );
    }
}
