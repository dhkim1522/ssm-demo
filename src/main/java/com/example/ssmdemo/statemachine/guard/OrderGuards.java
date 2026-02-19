package com.example.ssmdemo.statemachine.guard;

import com.example.ssmdemo.domain.order.entity.Order;
import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 주문 상태 전이 Guard (조건 검증)
 */
@Slf4j
@Component
public class OrderGuards {

    /**
     * 결제 가능 여부 검증
     * - 금액이 0보다 커야 함
     * - 결제 수단이 설정되어 있어야 함
     */
    public Guard<OrderStatus, OrderEvent> paymentValidGuard() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                log.warn("[Guard] 결제 검증 실패: 주문 정보 없음");
                return false;
            }

            // 금액 검증
            if (order.getTotalAmount() == null ||
                order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[Guard] 결제 검증 실패: 유효하지 않은 금액 - orderId={}, amount={}",
                    order.getId(), order.getTotalAmount());
                return false;
            }

            // 결제 수단 검증
            if (order.getPaymentMethod() == null || order.getPaymentMethod().isBlank()) {
                log.warn("[Guard] 결제 검증 실패: 결제 수단 미설정 - orderId={}", order.getId());
                return false;
            }

            log.info("[Guard] 결제 검증 통과 - orderId={}, amount={}, method={}",
                order.getId(), order.getTotalAmount(), order.getPaymentMethod());
            return true;
        };
    }

    /**
     * 재고 확인 Guard (시연용 - 항상 true)
     */
    public Guard<OrderStatus, OrderEvent> stockAvailableGuard() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return false;
            }

            // 시연용: 항상 재고 있음으로 처리
            log.info("[Guard] 재고 확인 통과 - orderId={}, productId={}, quantity={}",
                order.getId(), order.getProductId(), order.getQuantity());
            return true;
        };
    }

    /**
     * 취소 가능 여부 Guard
     * - 배송 시작 전에만 취소 가능
     */
    public Guard<OrderStatus, OrderEvent> cancellableGuard() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return false;
            }

            boolean cancellable = order.getShippedAt() == null;

            if (cancellable) {
                log.info("[Guard] 취소 가능 - orderId={}", order.getId());
            } else {
                log.warn("[Guard] 취소 불가: 이미 배송 시작됨 - orderId={}, shippedAt={}",
                    order.getId(), order.getShippedAt());
            }

            return cancellable;
        };
    }

    /**
     * 반품 가능 여부 Guard
     * - 배송 완료 후 7일 이내만 반품 가능 (시연용: 항상 true)
     */
    public Guard<OrderStatus, OrderEvent> returnableGuard() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return false;
            }

            // 시연용: 항상 반품 가능
            log.info("[Guard] 반품 가능 - orderId={}", order.getId());
            return true;
        };
    }

    private Order getOrder(StateContext<OrderStatus, OrderEvent> context) {
        return (Order) context.getExtendedState().getVariables().get("order");
    }
}
