package com.example.ssmdemo.statemachine.action;

import com.example.ssmdemo.domain.order.entity.Order;
import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 주문 상태 전이 Action (부수 효과 실행)
 */
@Slf4j
@Component
public class OrderActions {

    /**
     * 결제 처리 Action
     */
    public Action<OrderStatus, OrderEvent> processPaymentAction() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                log.error("[Action] 결제 처리 실패: 주문 정보 없음");
                return;
            }

            // 결제 처리 (시연용: 가상 결제 ID 생성)
            String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            order.markAsPaid(paymentId);

            log.info("============================================");
            log.info("[Action] 결제 처리 완료");
            log.info("  - 주문 ID: {}", order.getId());
            log.info("  - 결제 ID: {}", paymentId);
            log.info("  - 결제 금액: {}", order.getTotalAmount());
            log.info("  - 결제 수단: {}", order.getPaymentMethod());
            log.info("============================================");
        };
    }

    /**
     * 재고 차감 Action
     */
    public Action<OrderStatus, OrderEvent> deductStockAction() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return;
            }

            order.markAsShipped();

            log.info("============================================");
            log.info("[Action] 재고 차감 완료");
            log.info("  - 주문 ID: {}", order.getId());
            log.info("  - 상품 ID: {}", order.getProductId());
            log.info("  - 차감 수량: {}", order.getQuantity());
            log.info("============================================");
        };
    }

    /**
     * 알림 발송 Action
     */
    public Action<OrderStatus, OrderEvent> sendNotificationAction() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return;
            }

            OrderStatus targetStatus = context.getTarget().getId();
            String message = String.format("주문 상태가 [%s](%s)(으)로 변경되었습니다.",
                targetStatus.name(), targetStatus.getDescription());

            log.info("============================================");
            log.info("[Action] 알림 발송");
            log.info("  - 수신자: {}", order.getCustomerEmail());
            log.info("  - 메시지: {}", message);
            log.info("============================================");
        };
    }

    /**
     * 환불 처리 Action
     */
    public Action<OrderStatus, OrderEvent> processRefundAction() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return;
            }

            String refundId = "REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            log.info("============================================");
            log.info("[Action] 환불 처리 완료");
            log.info("  - 주문 ID: {}", order.getId());
            log.info("  - 환불 ID: {}", refundId);
            log.info("  - 환불 금액: {}", order.getTotalAmount());
            log.info("  - 원 결제 ID: {}", order.getPaymentId());
            log.info("============================================");
        };
    }

    /**
     * 배송 완료 Action
     */
    public Action<OrderStatus, OrderEvent> deliveryCompleteAction() {
        return context -> {
            Order order = getOrder(context);
            if (order == null) {
                return;
            }

            order.markAsDelivered();

            log.info("============================================");
            log.info("[Action] 배송 완료 처리");
            log.info("  - 주문 ID: {}", order.getId());
            log.info("  - 배송 완료 시각: {}", order.getDeliveredAt());
            log.info("============================================");
        };
    }

    /**
     * 에러 처리 Action
     */
    public Action<OrderStatus, OrderEvent> errorAction() {
        return context -> {
            Exception exception = context.getException();
            Order order = getOrder(context);

            log.error("============================================");
            log.error("[Action] 상태 전이 에러 발생!");
            log.error("  - 주문 ID: {}", order != null ? order.getId() : "UNKNOWN");
            log.error("  - 에러: {}", exception != null ? exception.getMessage() : "UNKNOWN");
            log.error("============================================");

            if (exception != null) {
                context.getExtendedState().getVariables().put("lastError", exception);
            }
        };
    }

    private Order getOrder(StateContext<OrderStatus, OrderEvent> context) {
        return (Order) context.getExtendedState().getVariables().get("order");
    }
}
