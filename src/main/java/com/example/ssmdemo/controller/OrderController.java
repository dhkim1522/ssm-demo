package com.example.ssmdemo.controller;

import com.example.ssmdemo.controller.dto.CreateOrderRequest;
import com.example.ssmdemo.controller.dto.OrderResponse;
import com.example.ssmdemo.domain.order.entity.Order;
import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 주문 API 컨트롤러
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 생성
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(
            request.productId(),
            request.quantity(),
            request.amount(),
            request.customerEmail(),
            request.paymentMethod()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(OrderResponse.from(order));
    }

    /**
     * 주문 조회
     * GET /api/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        Order order = orderService.getOrder(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    /**
     * 전체 주문 조회
     * GET /api/orders
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<Order> orders = orderService.getAllOrders();
        return ResponseEntity.ok(
            orders.stream().map(OrderResponse::from).toList()
        );
    }

    /**
     * 결제 처리
     * POST /api/orders/{orderId}/pay
     */
    @PostMapping("/{orderId}/pay")
    public ResponseEntity<OrderResponse> pay(@PathVariable String orderId) {
        Order order = orderService.pay(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    /**
     * 배송 시작
     * POST /api/orders/{orderId}/ship
     */
    @PostMapping("/{orderId}/ship")
    public ResponseEntity<OrderResponse> ship(@PathVariable String orderId) {
        Order order = orderService.ship(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    /**
     * 배송 완료
     * POST /api/orders/{orderId}/deliver
     */
    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<OrderResponse> deliver(@PathVariable String orderId) {
        Order order = orderService.deliver(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    /**
     * 주문 취소
     * POST /api/orders/{orderId}/cancel
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable String orderId) {
        Order order = orderService.cancel(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    /**
     * 반품 처리
     * POST /api/orders/{orderId}/return
     */
    @PostMapping("/{orderId}/return")
    public ResponseEntity<OrderResponse> returnOrder(@PathVariable String orderId) {
        Order order = orderService.returnOrder(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    /**
     * 가능한 이벤트 조회
     * GET /api/orders/{orderId}/available-events
     */
    @GetMapping("/{orderId}/available-events")
    public ResponseEntity<Map<String, Object>> getAvailableEvents(@PathVariable String orderId) {
        Order order = orderService.getOrder(orderId);
        List<OrderEvent> events = orderService.getAvailableEvents(orderId);

        return ResponseEntity.ok(Map.of(
            "orderId", orderId,
            "currentStatus", order.getStatus().name(),
            "currentStatusDescription", order.getStatus().getDescription(),
            "availableEvents", events.stream()
                .map(e -> Map.of(
                    "event", e.name(),
                    "description", e.getDescription()
                ))
                .toList()
        ));
    }
}
