package com.example.ssmdemo.service;

import com.example.ssmdemo.domain.order.entity.Order;
import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import com.example.ssmdemo.domain.order.repository.OrderRepository;
import com.example.ssmdemo.exception.InvalidStateTransitionException;
import com.example.ssmdemo.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 주문 서비스
 * State Machine을 활용한 주문 상태 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;
    private final OrderRepository orderRepository;

    /**
     * 주문 생성
     */
    @Transactional
    public Order createOrder(String productId, Integer quantity, BigDecimal amount,
                            String customerEmail, String paymentMethod) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Order order = Order.builder()
            .id(orderId)
            .productId(productId)
            .quantity(quantity)
            .totalAmount(amount)
            .customerEmail(customerEmail)
            .paymentMethod(paymentMethod)
            .status(OrderStatus.CREATED)
            .build();

        Order savedOrder = orderRepository.save(order);
        log.info("주문 생성 완료 - orderId: {}, status: {}", savedOrder.getId(), savedOrder.getStatus());

        return savedOrder;
    }

    /**
     * 주문 조회
     */
    @Transactional(readOnly = true)
    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * 전체 주문 조회
     */
    @Transactional(readOnly = true)
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    /**
     * 결제 처리
     */
    @Transactional
    public Order pay(String orderId) {
        Order order = getOrder(orderId);
        log.info("===== 결제 시작 - orderId: {}, 현재 상태: {} =====", orderId, order.getStatus());

        sendEvent(order, OrderEvent.PAY);

        Order updatedOrder = orderRepository.save(order);
        log.info("===== 결제 완료 - orderId: {}, 변경된 상태: {} =====", orderId, updatedOrder.getStatus());

        return updatedOrder;
    }

    /**
     * 배송 시작
     */
    @Transactional
    public Order ship(String orderId) {
        Order order = getOrder(orderId);
        log.info("===== 배송 시작 - orderId: {}, 현재 상태: {} =====", orderId, order.getStatus());

        sendEvent(order, OrderEvent.SHIP);

        Order updatedOrder = orderRepository.save(order);
        log.info("===== 배송 처리 완료 - orderId: {}, 변경된 상태: {} =====", orderId, updatedOrder.getStatus());

        return updatedOrder;
    }

    /**
     * 배송 완료
     */
    @Transactional
    public Order deliver(String orderId) {
        Order order = getOrder(orderId);
        log.info("===== 배송 완료 처리 - orderId: {}, 현재 상태: {} =====", orderId, order.getStatus());

        sendEvent(order, OrderEvent.DELIVER);

        Order updatedOrder = orderRepository.save(order);
        log.info("===== 배송 완료 - orderId: {}, 변경된 상태: {} =====", orderId, updatedOrder.getStatus());

        return updatedOrder;
    }

    /**
     * 주문 취소
     */
    @Transactional
    public Order cancel(String orderId) {
        Order order = getOrder(orderId);
        log.info("===== 주문 취소 시작 - orderId: {}, 현재 상태: {} =====", orderId, order.getStatus());

        sendEvent(order, OrderEvent.CANCEL);
        order.markAsCancelled();

        Order updatedOrder = orderRepository.save(order);
        log.info("===== 주문 취소 완료 - orderId: {}, 변경된 상태: {} =====", orderId, updatedOrder.getStatus());

        return updatedOrder;
    }

    /**
     * 반품 처리
     */
    @Transactional
    public Order returnOrder(String orderId) {
        Order order = getOrder(orderId);
        log.info("===== 반품 처리 시작 - orderId: {}, 현재 상태: {} =====", orderId, order.getStatus());

        sendEvent(order, OrderEvent.RETURN);
        order.markAsReturned();

        Order updatedOrder = orderRepository.save(order);
        log.info("===== 반품 완료 - orderId: {}, 변경된 상태: {} =====", orderId, updatedOrder.getStatus());

        return updatedOrder;
    }

    /**
     * 현재 상태에서 가능한 이벤트 목록 조회
     */
    public List<OrderEvent> getAvailableEvents(String orderId) {
        Order order = getOrder(orderId);
        StateMachine<OrderStatus, OrderEvent> sm = acquireStateMachine(order);

        return sm.getTransitions().stream()
            .filter(t -> t.getSource().getId() == order.getStatus())
            .map(t -> t.getTrigger().getEvent())
            .distinct()
            .toList();
    }

    // === Private Methods ===

    private void sendEvent(Order order, OrderEvent event) {
        StateMachine<OrderStatus, OrderEvent> sm = acquireStateMachine(order);

        Message<OrderEvent> message = MessageBuilder
            .withPayload(event)
            .setHeader("orderId", order.getId())
            .build();

        var result = sm.sendEvent(Mono.just(message)).blockLast();

        if (result == null || result.getResultType() != StateMachineEventResult.ResultType.ACCEPTED) {
            throw new InvalidStateTransitionException(
                String.format("이벤트 [%s]을(를) 처리할 수 없습니다. 현재 상태: [%s]",
                    event.name(), order.getStatus().name()));
        }

        // 상태 머신의 현재 상태를 엔티티에 반영
        order.updateStatus(sm.getState().getId());
    }

    private StateMachine<OrderStatus, OrderEvent> acquireStateMachine(Order order) {
        StateMachine<OrderStatus, OrderEvent> sm =
            stateMachineFactory.getStateMachine(order.getId());

        // 현재 주문 상태로 State Machine 초기화
        sm.stopReactively().block();
        sm.getStateMachineAccessor()
            .doWithAllRegions(accessor -> accessor
                .resetStateMachineReactively(
                    new org.springframework.statemachine.support.DefaultStateMachineContext<>(
                        order.getStatus(), null, null, null))
                .block());
        sm.startReactively().block();

        // ExtendedState에 주문 정보 저장 (Guard/Action에서 사용)
        sm.getExtendedState().getVariables().put("order", order);

        return sm;
    }
}
