package com.example.ssmdemo.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("주문을 찾을 수 없습니다: " + orderId);
    }
}
