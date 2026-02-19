package com.example.ssmdemo.domain.order.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 주문 이벤트
 */
@Getter
@RequiredArgsConstructor
public enum OrderEvent {

    PAY("결제"),
    SHIP("배송 시작"),
    DELIVER("배송 완료"),
    CANCEL("취소"),
    RETURN("반품"),
    REFUND("환불");

    private final String description;
}
