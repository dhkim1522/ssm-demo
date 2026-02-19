package com.example.ssmdemo.controller.dto;

import java.math.BigDecimal;

public record CreateOrderRequest(
    String productId,
    Integer quantity,
    BigDecimal amount,
    String customerEmail,
    String paymentMethod
) {}
