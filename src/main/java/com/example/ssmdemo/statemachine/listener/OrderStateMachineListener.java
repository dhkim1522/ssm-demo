package com.example.ssmdemo.statemachine.listener;

import com.example.ssmdemo.domain.order.enums.OrderEvent;
import com.example.ssmdemo.domain.order.enums.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.transition.Transition;

/**
 * 주문 State Machine 리스너 (모니터링/로깅)
 */
@Slf4j
public class OrderStateMachineListener
        extends StateMachineListenerAdapter<OrderStatus, OrderEvent> {

    @Override
    public void stateChanged(State<OrderStatus, OrderEvent> from,
                            State<OrderStatus, OrderEvent> to) {
        if (from == null) {
            log.info("┌──────────────────────────────────────────┐");
            log.info("│ [STATE] 초기 상태 설정: {}                  │", to.getId());
            log.info("└──────────────────────────────────────────┘");
        } else {
            log.info("┌──────────────────────────────────────────┐");
            log.info("│ [STATE] 상태 변경: {} → {}                 │", from.getId(), to.getId());
            log.info("└──────────────────────────────────────────┘");
        }
    }

    @Override
    public void eventNotAccepted(Message<OrderEvent> event) {
        log.warn("┌──────────────────────────────────────────┐");
        log.warn("│ [REJECTED] 이벤트 거부됨: {}                │", event.getPayload());
        log.warn("│ (현재 상태에서 처리할 수 없는 이벤트)            │");
        log.warn("└──────────────────────────────────────────┘");
    }

    @Override
    public void transitionStarted(Transition<OrderStatus, OrderEvent> transition) {
        if (transition.getSource() != null && transition.getTarget() != null) {
            log.debug("[TRANSITION] 시작: {} → {} [이벤트: {}]",
                transition.getSource().getId(),
                transition.getTarget().getId(),
                transition.getTrigger() != null ? transition.getTrigger().getEvent() : "N/A");
        }
    }

    @Override
    public void transitionEnded(Transition<OrderStatus, OrderEvent> transition) {
        if (transition.getSource() != null && transition.getTarget() != null) {
            log.debug("[TRANSITION] 완료: {} → {}",
                transition.getSource().getId(),
                transition.getTarget().getId());
        }
    }

    @Override
    public void stateMachineStarted(StateMachine<OrderStatus, OrderEvent> stateMachine) {
        log.info("[SSM] State Machine 시작 - ID: {}", stateMachine.getId());
    }

    @Override
    public void stateMachineStopped(StateMachine<OrderStatus, OrderEvent> stateMachine) {
        log.info("[SSM] State Machine 종료 - ID: {}", stateMachine.getId());
    }

    @Override
    public void stateMachineError(StateMachine<OrderStatus, OrderEvent> stateMachine,
                                  Exception exception) {
        log.error("┌──────────────────────────────────────────┐");
        log.error("│ [ERROR] State Machine 에러 발생!           │");
        log.error("│ Machine ID: {}                           │", stateMachine.getId());
        log.error("│ Error: {}                                │", exception.getMessage());
        log.error("└──────────────────────────────────────────┘", exception);
    }

    @Override
    public void stateContext(StateContext<OrderStatus, OrderEvent> stateContext) {
        // 상세 컨텍스트 로깅 (필요 시 활성화)
    }
}
