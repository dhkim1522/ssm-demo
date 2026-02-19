package com.example.ssmdemo.statemachine.config;

import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import com.example.ssmdemo.statemachine.action.OrderActions;
import com.example.ssmdemo.statemachine.guard.OrderGuards;
import com.example.ssmdemo.statemachine.listener.OrderStateMachineListener;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * 주문 State Machine 설정
 *
 * 상태 전이 다이어그램:
 *
 *     ┌──────────┐
 *     │ CREATED  │ ─────────────────────────────────┐
 *     └────┬─────┘                                   │
 *          │ PAY                                     │ CANCEL
 *          ▼                                         ▼
 *     ┌──────────┐                             ┌──────────┐
 *     │   PAID   │ ────────────────────────────│ CANCELLED│
 *     └────┬─────┘  CANCEL (cancellableGuard)  └──────────┘
 *          │ SHIP
 *          ▼
 *     ┌──────────┐
 *     │ SHIPPED  │
 *     └────┬─────┘
 *          │ DELIVER
 *          ▼
 *     ┌──────────┐
 *     │ DELIVERED│
 *     └────┬─────┘
 *          │ RETURN
 *          ▼
 *     ┌──────────┐
 *     │ RETURNED │
 *     └──────────┘
 */
@Configuration
@EnableStateMachineFactory(name = "orderStateMachineFactory")
@RequiredArgsConstructor
public class OrderStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<OrderStatus, OrderEvent> {

    private final OrderGuards guards;
    private final OrderActions actions;

    @Override
    public void configure(StateMachineConfigurationConfigurer<OrderStatus, OrderEvent> config)
            throws Exception {
        config
            .withConfiguration()
            .machineId("orderStateMachine")
            .autoStartup(true)
            .listener(new OrderStateMachineListener());
    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderStatus, OrderEvent> states)
            throws Exception {
        states
            .withStates()
            .initial(OrderStatus.CREATED)
            .states(EnumSet.allOf(OrderStatus.class))
            .end(OrderStatus.CANCELLED)
            .end(OrderStatus.RETURNED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStatus, OrderEvent> transitions)
            throws Exception {
        transitions
            // ===== CREATED 상태에서의 전이 =====

            // CREATED → PAID: 결제
            .withExternal()
                .source(OrderStatus.CREATED)
                .target(OrderStatus.PAID)
                .event(OrderEvent.PAY)
                .guard(guards.paymentValidGuard())
                .action(actions.processPaymentAction(), actions.errorAction())
                .action(actions.sendNotificationAction())
                .and()

            // CREATED → CANCELLED: 주문 취소
            .withExternal()
                .source(OrderStatus.CREATED)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .action(actions.sendNotificationAction())
                .and()

            // ===== PAID 상태에서의 전이 =====

            // PAID → SHIPPED: 배송 시작
            .withExternal()
                .source(OrderStatus.PAID)
                .target(OrderStatus.SHIPPED)
                .event(OrderEvent.SHIP)
                .guard(guards.stockAvailableGuard())
                .action(actions.deductStockAction(), actions.errorAction())
                .action(actions.sendNotificationAction())
                .and()

            // PAID → CANCELLED: 결제 후 취소 (환불)
            .withExternal()
                .source(OrderStatus.PAID)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .guard(guards.cancellableGuard())
                .action(actions.processRefundAction(), actions.errorAction())
                .action(actions.sendNotificationAction())
                .and()

            // ===== SHIPPED 상태에서의 전이 =====

            // SHIPPED → DELIVERED: 배송 완료
            .withExternal()
                .source(OrderStatus.SHIPPED)
                .target(OrderStatus.DELIVERED)
                .event(OrderEvent.DELIVER)
                .action(actions.deliveryCompleteAction())
                .action(actions.sendNotificationAction())
                .and()

            // ===== DELIVERED 상태에서의 전이 =====

            // DELIVERED → RETURNED: 반품
            .withExternal()
                .source(OrderStatus.DELIVERED)
                .target(OrderStatus.RETURNED)
                .event(OrderEvent.RETURN)
                .guard(guards.returnableGuard())
                .action(actions.processRefundAction(), actions.errorAction())
                .action(actions.sendNotificationAction());
    }
}
