package com.example.ssmdemo;

import com.example.ssmdemo.domain.order.entity.Order;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import com.example.ssmdemo.exception.InvalidStateTransitionException;
import com.example.ssmdemo.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("주문 서비스 테스트")
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Nested
    @DisplayName("주문 생성")
    class CreateOrder {

        @Test
        @DisplayName("주문 생성 시 CREATED 상태로 생성된다")
        void 성공_주문_생성시_CREATED_상태() {
            // when
            Order order = orderService.createOrder(
                "PRODUCT-001",
                2,
                new BigDecimal("50000"),
                "test@example.com",
                "CARD"
            );

            // then
            assertThat(order.getId()).startsWith("ORD-");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getProductId()).isEqualTo("PRODUCT-001");
            assertThat(order.getQuantity()).isEqualTo(2);
            assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        }
    }

    @Nested
    @DisplayName("결제 처리")
    class PayOrder {

        @Test
        @DisplayName("CREATED 상태에서 결제 시 PAID로 전이된다")
        void 성공_CREATED에서_결제시_PAID로_전이() {
            // given
            Order order = createOrder();

            // when
            Order paidOrder = orderService.pay(order.getId());

            // then
            assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(paidOrder.getPaymentId()).isNotNull();
            assertThat(paidOrder.getPaidAt()).isNotNull();
        }

        @Test
        @DisplayName("PAID 상태에서 결제 시 예외가 발생한다")
        void 실패_PAID에서_결제시_예외발생() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());

            // when & then
            assertThatThrownBy(() -> orderService.pay(order.getId()))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("PAY");
        }
    }

    @Nested
    @DisplayName("배송 처리")
    class ShipOrder {

        @Test
        @DisplayName("PAID 상태에서 배송 시 SHIPPED로 전이된다")
        void 성공_PAID에서_배송시_SHIPPED로_전이() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());

            // when
            Order shippedOrder = orderService.ship(order.getId());

            // then
            assertThat(shippedOrder.getStatus()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(shippedOrder.getShippedAt()).isNotNull();
        }

        @Test
        @DisplayName("CREATED 상태에서 배송 시 예외가 발생한다")
        void 실패_CREATED에서_배송시_예외발생() {
            // given
            Order order = createOrder();

            // when & then
            assertThatThrownBy(() -> orderService.ship(order.getId()))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SHIP");
        }
    }

    @Nested
    @DisplayName("배송 완료")
    class DeliverOrder {

        @Test
        @DisplayName("SHIPPED 상태에서 배송완료 시 DELIVERED로 전이된다")
        void 성공_SHIPPED에서_배송완료시_DELIVERED로_전이() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());
            orderService.ship(order.getId());

            // when
            Order deliveredOrder = orderService.deliver(order.getId());

            // then
            assertThat(deliveredOrder.getStatus()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(deliveredOrder.getDeliveredAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("주문 취소")
    class CancelOrder {

        @Test
        @DisplayName("CREATED 상태에서 취소 시 CANCELLED로 전이된다")
        void 성공_CREATED에서_취소시_CANCELLED로_전이() {
            // given
            Order order = createOrder();

            // when
            Order cancelledOrder = orderService.cancel(order.getId());

            // then
            assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelledOrder.getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("PAID 상태에서 취소 시 CANCELLED로 전이된다 (환불)")
        void 성공_PAID에서_취소시_CANCELLED로_전이_환불() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());

            // when
            Order cancelledOrder = orderService.cancel(order.getId());

            // then
            assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("SHIPPED 상태에서 취소 시 예외가 발생한다")
        void 실패_SHIPPED에서_취소시_예외발생() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());
            orderService.ship(order.getId());

            // when & then
            assertThatThrownBy(() -> orderService.cancel(order.getId()))
                .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("반품 처리")
    class ReturnOrder {

        @Test
        @DisplayName("DELIVERED 상태에서 반품 시 RETURNED로 전이된다")
        void 성공_DELIVERED에서_반품시_RETURNED로_전이() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());
            orderService.ship(order.getId());
            orderService.deliver(order.getId());

            // when
            Order returnedOrder = orderService.returnOrder(order.getId());

            // then
            assertThat(returnedOrder.getStatus()).isEqualTo(OrderStatus.RETURNED);
        }

        @Test
        @DisplayName("PAID 상태에서 반품 시 예외가 발생한다")
        void 실패_PAID에서_반품시_예외발생() {
            // given
            Order order = createOrder();
            orderService.pay(order.getId());

            // when & then
            assertThatThrownBy(() -> orderService.returnOrder(order.getId()))
                .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    // === Helper Methods ===

    private Order createOrder() {
        return orderService.createOrder(
            "PRODUCT-001",
            2,
            new BigDecimal("50000"),
            "test@example.com",
            "CARD"
        );
    }
}
