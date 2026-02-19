package com.example.ssmdemo;

import com.example.ssmdemo.domain.order.entity.Order;
import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("주문 State Machine 테스트")
class OrderStateMachineTest {

    @Autowired
    private StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    private StateMachine<OrderStatus, OrderEvent> sm;
    private Order order;

    @BeforeEach
    void setUp() {
        sm = stateMachineFactory.getStateMachine("test-order");
        order = createTestOrder();
        sm.getExtendedState().getVariables().put("order", order);
        sm.startReactively().block();
    }

    @Nested
    @DisplayName("CREATED 상태에서")
    class CreatedState {

        @BeforeEach
        void setUpState() {
            setInitialState(OrderStatus.CREATED);
        }

        @Test
        @DisplayName("PAY 이벤트 발생 시 PAID로 전이된다")
        void 성공_결제시_PAID로_전이() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.PAY);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        @DisplayName("CANCEL 이벤트 발생 시 CANCELLED로 전이된다")
        void 성공_취소시_CANCELLED로_전이() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("SHIP 이벤트는 거부된다 (결제 전 배송 불가)")
        void 실패_배송_이벤트는_거부됨() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.SHIP);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CREATED);
        }

        @Test
        @DisplayName("DELIVER 이벤트는 거부된다")
        void 실패_배송완료_이벤트는_거부됨() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.DELIVER);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("PAID 상태에서")
    class PaidState {

        @BeforeEach
        void setUpState() {
            setInitialState(OrderStatus.PAID);
        }

        @Test
        @DisplayName("SHIP 이벤트 발생 시 SHIPPED로 전이된다")
        void 성공_배송시작시_SHIPPED로_전이() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.SHIP);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("CANCEL 이벤트 발생 시 CANCELLED로 전이된다 (환불 처리)")
        void 성공_취소시_CANCELLED로_전이_환불_처리() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("PAY 이벤트는 거부된다 (이미 결제됨)")
        void 실패_결제_이벤트는_거부됨() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.PAY);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.PAID);
        }
    }

    @Nested
    @DisplayName("SHIPPED 상태에서")
    class ShippedState {

        @BeforeEach
        void setUpState() {
            setInitialState(OrderStatus.SHIPPED);
            order.markAsShipped(); // shippedAt 설정
        }

        @Test
        @DisplayName("DELIVER 이벤트 발생 시 DELIVERED로 전이된다")
        void 성공_배송완료시_DELIVERED로_전이() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.DELIVER);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("CANCEL 이벤트는 거부된다 (배송 중 취소 불가)")
        void 실패_취소_이벤트는_거부됨_배송중_취소불가() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.SHIPPED);
        }
    }

    @Nested
    @DisplayName("DELIVERED 상태에서")
    class DeliveredState {

        @BeforeEach
        void setUpState() {
            setInitialState(OrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("RETURN 이벤트 발생 시 RETURNED로 전이된다")
        void 성공_반품시_RETURNED로_전이() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.RETURN);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.RETURNED);
        }

        @Test
        @DisplayName("CANCEL 이벤트는 거부된다 (배송 완료 후 취소 불가)")
        void 실패_취소_이벤트는_거부됨() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.DELIVERED);
        }
    }

    @Nested
    @DisplayName("CANCELLED 상태에서")
    class CancelledState {

        @BeforeEach
        void setUpState() {
            setInitialState(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("PAY 이벤트는 거부된다 (종료 상태)")
        void 실패_결제_이벤트는_거부됨_종료상태() {
            // when
            boolean accepted = sm.sendEvent(OrderEvent.PAY);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("모든 이벤트가 거부된다 (종료 상태)")
        void 실패_모든_이벤트_거부됨_종료상태() {
            // when & then
            assertThat(sm.sendEvent(OrderEvent.PAY)).isFalse();
            assertThat(sm.sendEvent(OrderEvent.SHIP)).isFalse();
            assertThat(sm.sendEvent(OrderEvent.DELIVER)).isFalse();
            assertThat(sm.sendEvent(OrderEvent.CANCEL)).isFalse();
            assertThat(sm.sendEvent(OrderEvent.RETURN)).isFalse();

            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("전체 주문 흐름 테스트")
    class FullFlowTest {

        @Test
        @DisplayName("정상 주문 흐름: CREATED → PAID → SHIPPED → DELIVERED")
        void 성공_CREATED에서_DELIVERED까지_정상_흐름() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when - CREATED → PAID
            sm.sendEvent(OrderEvent.PAY);
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.PAID);

            // when - PAID → SHIPPED
            sm.sendEvent(OrderEvent.SHIP);
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.SHIPPED);

            // when - SHIPPED → DELIVERED
            sm.sendEvent(OrderEvent.DELIVER);
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("반품 흐름: CREATED → PAID → SHIPPED → DELIVERED → RETURNED")
        void 성공_반품_흐름() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when - 전체 배송 흐름
            sm.sendEvent(OrderEvent.PAY);
            sm.sendEvent(OrderEvent.SHIP);
            sm.sendEvent(OrderEvent.DELIVER);
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.DELIVERED);

            // when - 반품
            sm.sendEvent(OrderEvent.RETURN);
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.RETURNED);
        }

        @Test
        @DisplayName("결제 전 취소 흐름: CREATED → CANCELLED")
        void 성공_결제전_취소_흐름() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when
            sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("결제 후 취소 흐름: CREATED → PAID → CANCELLED (환불)")
        void 성공_결제후_취소_흐름_환불() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when
            sm.sendEvent(OrderEvent.PAY);
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.PAID);

            sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    // === Helper Methods ===

    private void setInitialState(OrderStatus status) {
        sm.stopReactively().block();
        sm.getStateMachineAccessor()
            .doWithAllRegions(accessor -> accessor
                .resetStateMachineReactively(
                    new DefaultStateMachineContext<>(status, null, null, null))
                .block());
        sm.startReactively().block();
    }

    private Order createTestOrder() {
        return Order.builder()
            .id("test-order-1")
            .productId("product-1")
            .quantity(2)
            .totalAmount(new BigDecimal("50000"))
            .customerEmail("test@example.com")
            .paymentMethod("CARD")
            .status(OrderStatus.CREATED)
            .build();
    }
}
