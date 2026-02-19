package com.example.ssmdemo.domain.order.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 주문 상태
 */
@Getter
@RequiredArgsConstructor
public enum OrderStatus {

    CREATED("주문 생성"),
    PAID("결제 완료"),
    SHIPPED("배송 시작"),
    DELIVERED("배송 완료"),
    CANCELLED("주문 취소"),
    RETURNED("반품 완료");

    private final String description;
}
