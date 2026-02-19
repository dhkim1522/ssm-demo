# Spring State Machine 기술 가이드

> 본 문서는 Spring State Machine(SSM)에 대한 팀 내 기술 공유를 목적으로 작성되었습니다.

---

## 목차

1. [Spring State Machine 이란?](#1-spring-state-machine-이란)
2. [Spring State Machine 작동 원리](#2-spring-state-machine-작동-원리)
3. [Spring State Machine 활용 예제](#3-spring-state-machine-활용-예제)
4. [Spring State Machine을 사용하면 좋은 상황](#4-spring-state-machine을-사용하면-좋은-상황)
5. [Spring State Machine의 장단점](#5-spring-state-machine의-장단점)
6. [정리 요약](#6-정리-요약)

---

## 1. Spring State Machine 이란?

### 1.1 개요

**Spring State Machine(SSM)**은 Spring Framework 기반의 상태 머신(State Machine) 구현체입니다. 복잡한 상태 전이 로직을 선언적이고 체계적으로 관리할 수 있도록 도와주는 프레임워크입니다.

### 1.2 State Machine(상태 머신)의 기본 개념

상태 머신은 **유한 상태 오토마타(Finite State Automata, FSA)**의 구현으로, 다음 요소로 구성됩니다:

| 구성 요소 | 설명 | 예시 (주문) |
|-----------|------|------------|
| **State (상태)** | 시스템이 존재할 수 있는 상태 | CREATED, PAID, SHIPPED, DELIVERED |
| **Event (이벤트)** | 상태 전이를 유발하는 트리거 | PAY, SHIP, DELIVER, CANCEL |
| **Transition (전이)** | 한 상태에서 다른 상태로의 이동 | CREATED → PAID |
| **Guard (가드)** | 전이 가능 여부를 판단하는 조건 | 재고 확인, 결제 검증 |
| **Action (액션)** | 전이 시 실행되는 동작 | 결제 처리, 재고 차감, 알림 발송 |

### 1.3 상태 다이어그램 예시: 주문

```
┌─────────────────────────────────────────────────────────┐
│                    주문 상태 머신                          │
└─────────────────────────────────────────────────────────┘

    ┌──────────┐     CREATE      ┌──────────┐
    │  INIT    │ ───────────────→│ PENDING  │
    └──────────┘                 └──────────┘
                                      │
                         PAY          │         CANCEL
                    ┌─────────────────┴─────────────────┐
                    ↓                                   ↓
              ┌──────────┐                        ┌──────────┐
              │   PAID   │                        │ CANCELLED│
              └──────────┘                        └──────────┘
                    │
                    │ SHIP
                    ↓
              ┌──────────┐
              │ SHIPPED  │
              └──────────┘
                    │
                    │ DELIVER
                    ↓
              ┌──────────┐
              │ DELIVERED│
              └──────────┘
```

### 1.4 Spring State Machine의 특징

- **Spring 생태계 통합**: Spring Boot, Spring Security, Spring Data와 자연스러운 통합
- **선언적 설정**: Java Config 또는 Builder DSL로 상태 머신 정의
- **계층적 상태**: 중첩 상태(Nested State) 지원
- **영속화 지원**: Redis, JPA 등을 통한 상태 저장
- **분산 환경 지원**: 클러스터 환경에서의 상태 동기화
- **이벤트 기반**: Spring ApplicationEvent와 통합

---

## 2. Spring State Machine 작동 원리

### 2.1 핵심 컴포넌트

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring State Machine 아키텍처                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │ StateMachine    │    │ StateMachine    │                    │
│  │ Factory         │───→│ (Instance)      │                    │
│  │                 │    │ 주문ID별 생성    │                      │
│  └─────────────────┘    └─────────────────┘                    │
│                               │                                │
│                               ↓                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    State Machine Core                   │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐     │   │
│  │  │ States  │  │ Events  │  │ Guards  │  │ Actions │     │   │
│  │  │ CREATED │  │ PAY     │  │결제검증 │  │결제처리 │    │    │   │
│  │  │ PAID    │  │ SHIP    │  │재고확인 │  │재고차감 │    │    │   │
│  │  │ SHIPPED │  │ DELIVER │  │배송검증 │  │알림발송 │    │    │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘     │   │
│  │                      │                                  │   │
│  │                      ↓                                  │   │
│  │              ┌─────────────┐                            │   │
│  │              │ Transitions │                            │   │
│  │              └─────────────┘                            │   │
│  └─────────────────────────────────────────────────────────┘   │
│                               │                                │
│                               ↓                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Persistence Layer                    │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐                  │   │
│  │  │  Redis  │  │   JPA   │  │ In-Memory│                 │   │
│  │  └─────────┘  └─────────┘  └─────────┘                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 2.2 주요 인터페이스

#### StateMachine<S, E>

상태 머신의 핵심 인터페이스입니다.

```java
public interface StateMachine<S, E> {
    // 현재 상태 조회
    State<S, E> getState();

    // 이벤트 발송
    boolean sendEvent(E event);
    Flux<StateMachineEventResult<S, E>> sendEvent(Mono<Message<E>> event);

    // 상태 머신 시작/중지
    void start();
    void stop();

    // 확장 상태 (컨텍스트 데이터)
    ExtendedState getExtendedState();
}
```

#### StateMachineFactory<S, E>

엔티티별로 독립적인 상태 머신 인스턴스를 생성합니다.

```java
public interface StateMachineFactory<S, E> {
    // 엔티티 ID로 상태 머신 획득
    StateMachine<S, E> getStateMachine(String machineId);
}
```

#### Guard<S, E>

상태 전이 가능 여부를 판단하는 조건입니다.

```java
@FunctionalInterface
public interface Guard<S, E> {
    boolean evaluate(StateContext<S, E> context);
}

// 예시: 재고 확인 Guard
public class StockAvailableGuard implements Guard<OrderStatus, OrderEvent> {
    @Override
    public boolean evaluate(StateContext<OrderStatus, OrderEvent> context) {
        Order order = getOrder(context);
        return inventoryService.isAvailable(order.getProductId(), order.getQuantity());
    }
}
```

#### Action<S, E>

상태 전이 시 실행되는 동작입니다.

```java
@FunctionalInterface
public interface Action<S, E> {
    void execute(StateContext<S, E> context);
}

// 예시: 결제 처리 Action
public class ProcessPaymentAction implements Action<OrderStatus, OrderEvent> {
    @Override
    public void execute(StateContext<OrderStatus, OrderEvent> context) {
        Order order = getOrder(context);
        paymentService.process(order);
    }
}
```

### 2.3 상태 전이 흐름

```
┌─────────────────────────────────────────────────────────────┐
│                    상태 전이 처리 흐름                       │
└─────────────────────────────────────────────────────────────┘

  Event 수신        Guard 평가         Action 실행       상태 변경
      │                 │                  │                │
      ↓                 ↓                  ↓                ↓
  ┌───────────┐   ┌───────────────┐  ┌──────────────┐  ┌─────────┐
  │   Event   │──→│    Guard      │─→│   Actions    │─→│  State  │
  │    PAY    │   │ 결제 가능?     │  │ processPayment│  │  PAID   │
  └───────────┘   │ 재고 있음?     │  │ deductStock()│  └─────────┘
                  └───────────────┘  └──────────────┘
                        │
                        │ false (Guard 조건 불충족)
                        ↓
                   ┌─────────┐
                   │ Rejected│
                   │ (상태유지)│
                   └─────────┘

상세 흐름:
1. 클라이언트가 Event(예: PAY)를 StateMachine에 전송
2. StateMachine이 현재 상태(예: CREATED)에서 해당 Event를 처리할 수 있는 Transition 탐색
3. Transition에 정의된 Guard 조건 평가 (결제 가능 여부, 재고 확인 등)
4. Guard가 true를 반환하면 Transition 실행
5. Action 순차 실행 (결제 처리, 재고 차감, 알림 발송 등)
6. 상태 변경 (CREATED → PAID) 및 이벤트 발행
```

### 2.4 상태 전이 설정 방식

#### Java Config 방식

```java
@Configuration
@EnableStateMachine
public class OrderStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<OrderStatus, OrderEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<OrderStatus, OrderEvent> states)
            throws Exception {
        states
            .withStates()
            .initial(OrderStatus.CREATED)        // 초기 상태
            .state(OrderStatus.PAID)
            .state(OrderStatus.SHIPPED)
            .state(OrderStatus.DELIVERED)
            .end(OrderStatus.CANCELLED);         // 최종 상태
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStatus, OrderEvent> transitions)
            throws Exception {
        transitions
            // CREATED → PAID (결제 이벤트)
            .withExternal()
                .source(OrderStatus.CREATED)
                .target(OrderStatus.PAID)
                .event(OrderEvent.PAY)
                .guard(paymentValidGuard())
                .action(processPaymentAction())
                .and()

            // PAID → SHIPPED (배송 시작 이벤트)
            .withExternal()
                .source(OrderStatus.PAID)
                .target(OrderStatus.SHIPPED)
                .event(OrderEvent.SHIP)
                .guard(stockAvailableGuard())
                .action(deductStockAction())
                .action(sendNotificationAction());
    }
}
```

#### Builder DSL 방식

```java
StateMachine<OrderStatus, OrderEvent> buildStateMachine() {
    StateMachineBuilder.Builder<OrderStatus, OrderEvent> builder =
        StateMachineBuilder.builder();

    builder.configureStates()
        .withStates()
        .initial(OrderStatus.CREATED)
        .states(EnumSet.allOf(OrderStatus.class));

    builder.configureTransitions()
        .withExternal()
        .source(OrderStatus.CREATED)
        .target(OrderStatus.PAID)
        .event(OrderEvent.PAY);

    return builder.build();
}
```

### 2.5 계층적 상태 (Hierarchical States)

복잡한 상태를 하위 상태로 분리할 수 있습니다.

```
┌─────────────────────────────────────────┐
│              PROCESSING                  │  ← Parent State
│  ┌───────────┐      ┌───────────┐      │
│  │ VALIDATING│      │ PACKAGING │      │  ← Child States
│  │  (검증중) │      │  (포장중) │      │
│  └───────────┘      └───────────┘      │
└─────────────────────────────────────────┘
         │
         │ COMPLETE
         ↓
   ┌───────────┐
   │  SHIPPED  │
   │ (배송시작) │
   └───────────┘
```

```
states
    .withStates()
    .initial(OrderStatus.CREATED)
    .state(OrderStatus.PROCESSING)         // 부모 상태
    .state(OrderStatus.SHIPPED)
    .end(OrderStatus.DELIVERED)
    .and()
    .withStates()
    .parent(OrderStatus.PROCESSING)        // 부모 지정
    .initial(OrderStatus.VALIDATING)       // 하위 초기 상태
    .state(OrderStatus.PACKAGING);         // 하위 상태
```

### 2.6 상태 영속화

State Machine의 내부 상태 컨텍스트를 영속화하여 애플리케이션 재시작 후에도 상태를 유지할 수 있습니다.

> **참고:** 실제 비즈니스 엔티티(Order, AdGroup 등)는 JPA를 통해 RDB에 저장됩니다. 여기서의 영속화는 State Machine의 **내부 컨텍스트**(현재 상태, ExtendedState 변수 등)를 저장하는 것입니다.

#### Redis 기반 영속화

```java
@Configuration
public class StateMachinePersistConfig {

    @Bean
    public StateMachinePersister<OrderStatus, OrderEvent, String> redisStateMachinePersister(
            RedisConnectionFactory connectionFactory) {

        RedisStateMachineContextRepository<OrderStatus, OrderEvent> repository =
            new RedisStateMachineContextRepository<>(connectionFactory);

        return new DefaultStateMachinePersister<>(
            new RepositoryStateMachinePersist<>(repository)
        );
    }
}

// 사용
@Service
public class OrderService {

    private final StateMachinePersister<OrderStatus, OrderEvent, String> persister;

    public void processOrder(String orderId, OrderEvent event) {
        StateMachine<OrderStatus, OrderEvent> sm = stateMachineFactory.getStateMachine();

        // 저장된 상태 복원
        persister.restore(sm, orderId);

        // 이벤트 처리
        sm.sendEvent(event);

        // 상태 저장
        persister.persist(sm, orderId);
    }
}
```

---

## 3. Spring State Machine 활용 예제

### 3.1 의존성 추가

```gradle
dependencies {
    implementation 'org.springframework.statemachine:spring-statemachine-core:4.0.0'

    // Redis 영속화 사용 시
    implementation 'org.springframework.statemachine:spring-statemachine-data-redis:4.0.0'

    // JPA 영속화 사용 시
    implementation 'org.springframework.statemachine:spring-statemachine-data-jpa:4.0.0'
}
```

### 3.2 완전한 예제: 주문 상태 관리

#### Step 1: 상태와 이벤트 정의

```java
// 주문 상태
public enum OrderStatus {
    CREATED,      // 주문 생성
    PAID,         // 결제 완료
    SHIPPED,      // 배송 시작
    DELIVERED,    // 배송 완료
    CANCELLED,    // 주문 취소
    RETURNED      // 반품
}

// 주문 이벤트
public enum OrderEvent {
    PAY,          // 결제
    SHIP,         // 배송 시작
    DELIVER,      // 배송 완료
    CANCEL,       // 취소
    RETURN,       // 반품
    REFUND        // 환불
}
```

#### Step 2: Guard 정의

```java
@Component
@RequiredArgsConstructor
public class OrderGuards {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    /**
     * 결제 가능 여부 검증
     */
    @Bean
    public Guard<OrderStatus, OrderEvent> paymentValidGuard() {
        return context -> {
            Order order = getOrder(context);

            // 금액 검증
            if (order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return false;
            }

            // 결제 수단 검증
            return order.getPaymentMethod() != null;
        };
    }

    /**
     * 재고 확인 Guard
     */
    @Bean
    public Guard<OrderStatus, OrderEvent> stockAvailableGuard() {
        return context -> {
            Order order = getOrder(context);
            return inventoryService.isAvailable(
                order.getProductId(),
                order.getQuantity()
            );
        };
    }

    /**
     * 취소 가능 여부 Guard
     */
    @Bean
    public Guard<OrderStatus, OrderEvent> cancellableGuard() {
        return context -> {
            Order order = getOrder(context);
            // 배송 시작 전에만 취소 가능
            return order.getShippedAt() == null;
        };
    }

    private Order getOrder(StateContext<OrderStatus, OrderEvent> context) {
        return (Order) context.getExtendedState().getVariables().get("order");
    }
}
```

#### Step 3: Action 정의

```java
@Component
@RequiredArgsConstructor
public class OrderActions {

    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;

    /**
     * 결제 처리 Action
     */
    @Bean
    public Action<OrderStatus, OrderEvent> processPaymentAction() {
        return context -> {
            Order order = getOrder(context);

            PaymentResult result = paymentService.process(order);
            order.setPaidAt(LocalDateTime.now());
            order.setPaymentId(result.getPaymentId());

            log.info("Payment processed: orderId={}, paymentId={}",
                order.getId(), result.getPaymentId());
        };
    }

    /**
     * 재고 차감 Action
     */
    @Bean
    public Action<OrderStatus, OrderEvent> deductStockAction() {
        return context -> {
            Order order = getOrder(context);

            inventoryService.deduct(order.getProductId(), order.getQuantity());

            log.info("Stock deducted: orderId={}, productId={}, quantity={}",
                order.getId(), order.getProductId(), order.getQuantity());
        };
    }

    /**
     * 알림 발송 Action
     */
    @Bean
    public Action<OrderStatus, OrderEvent> sendNotificationAction() {
        return context -> {
            Order order = getOrder(context);
            OrderStatus targetStatus = context.getTarget().getId();

            notificationService.send(
                order.getCustomerEmail(),
                "주문 상태가 " + targetStatus.getDescription() + "(으)로 변경되었습니다."
            );
        };
    }

    /**
     * 환불 처리 Action
     */
    @Bean
    public Action<OrderStatus, OrderEvent> processRefundAction() {
        return context -> {
            Order order = getOrder(context);

            paymentService.refund(order.getPaymentId());
            order.setRefundedAt(LocalDateTime.now());

            log.info("Refund processed: orderId={}", order.getId());
        };
    }

    /**
     * 에러 처리 Action
     */
    @Bean
    public Action<OrderStatus, OrderEvent> errorAction() {
        return context -> {
            Exception exception = context.getException();
            Order order = getOrder(context);

            log.error("State transition error: orderId={}, error={}",
                order != null ? order.getId() : "unknown",
                exception != null ? exception.getMessage() : "unknown");

            context.getExtendedState().getVariables().put("lastError", exception);
        };
    }

    private Order getOrder(StateContext<OrderStatus, OrderEvent> context) {
        return (Order) context.getExtendedState().getVariables().get("order");
    }
}
```

#### Step 4: StateMachine 설정

```java
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
            .state(OrderStatus.PAID)
            .state(OrderStatus.SHIPPED)
            .state(OrderStatus.DELIVERED)
            .state(OrderStatus.RETURNED)
            .end(OrderStatus.CANCELLED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStatus, OrderEvent> transitions)
            throws Exception {
        transitions
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

            // SHIPPED → DELIVERED: 배송 완료
            .withExternal()
                .source(OrderStatus.SHIPPED)
                .target(OrderStatus.DELIVERED)
                .event(OrderEvent.DELIVER)
                .action(actions.sendNotificationAction())
                .and()

            // DELIVERED → RETURNED: 반품
            .withExternal()
                .source(OrderStatus.DELIVERED)
                .target(OrderStatus.RETURNED)
                .event(OrderEvent.RETURN)
                .action(actions.processRefundAction(), actions.errorAction())
                .action(actions.sendNotificationAction());
    }
}
```

#### Step 5: 리스너 정의 (모니터링/로깅)

```java
@Slf4j
public class OrderStateMachineListener
        extends StateMachineListenerAdapter<OrderStatus, OrderEvent> {

    @Override
    public void stateChanged(State<OrderStatus, OrderEvent> from,
                            State<OrderStatus, OrderEvent> to) {
        log.info("주문 상태 변경: {} → {}",
            from != null ? from.getId() : "NONE",
            to.getId());
    }

    @Override
    public void eventNotAccepted(Message<OrderEvent> event) {
        log.warn("이벤트 거부됨 (현재 상태에서 처리 불가): {}", event.getPayload());
    }

    @Override
    public void transitionStarted(Transition<OrderStatus, OrderEvent> transition) {
        log.debug("상태 전이 시작: {} → {} [이벤트: {}]",
            transition.getSource().getId(),
            transition.getTarget().getId(),
            transition.getTrigger().getEvent());
    }

    @Override
    public void transitionEnded(Transition<OrderStatus, OrderEvent> transition) {
        log.debug("상태 전이 완료: {} → {}",
            transition.getSource().getId(),
            transition.getTarget().getId());
    }

    @Override
    public void stateMachineError(StateMachine<OrderStatus, OrderEvent> stateMachine,
                                  Exception exception) {
        log.error("상태 머신 에러 발생", exception);
    }
}
```

#### Step 6: Service에서 사용

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;
    private final StateMachinePersister<OrderStatus, OrderEvent, String> persister;
    private final OrderRepository orderRepository;

    /**
     * 결제 처리
     */
    @Transactional
    public void pay(String orderId) {
        Order order = findOrderById(orderId);

        StateMachine<OrderStatus, OrderEvent> sm = acquireStateMachine(orderId, order);

        boolean accepted = sendEvent(sm, OrderEvent.PAY);

        if (!accepted) {
            throw new InvalidStateTransitionException(
                String.format("결제할 수 없는 상태입니다: %s", order.getStatus()));
        }

        persistAndSave(sm, order);
        log.info("결제 완료: orderId={}", orderId);
    }

    /**
     * 배송 시작
     */
    @Transactional
    public void ship(String orderId) {
        Order order = findOrderById(orderId);

        StateMachine<OrderStatus, OrderEvent> sm = acquireStateMachine(orderId, order);

        boolean accepted = sendEvent(sm, OrderEvent.SHIP);

        if (!accepted) {
            throw new InvalidStateTransitionException(
                String.format("배송을 시작할 수 없는 상태입니다: %s", order.getStatus()));
        }

        persistAndSave(sm, order);
        log.info("배송 시작: orderId={}", orderId);
    }

    /**
     * 주문 취소
     */
    @Transactional
    public void cancel(String orderId) {
        Order order = findOrderById(orderId);

        StateMachine<OrderStatus, OrderEvent> sm = acquireStateMachine(orderId, order);

        boolean accepted = sendEvent(sm, OrderEvent.CANCEL);

        if (!accepted) {
            throw new InvalidStateTransitionException(
                String.format("취소할 수 없는 상태입니다: %s", order.getStatus()));
        }

        persistAndSave(sm, order);
        log.info("주문 취소: orderId={}", orderId);
    }

    /**
     * 현재 상태에서 가능한 이벤트 목록 조회
     */
    public List<OrderEvent> getAvailableEvents(String orderId) {
        Order order = findOrderById(orderId);

        StateMachine<OrderStatus, OrderEvent> sm = acquireStateMachine(orderId, order);

        return sm.getTransitions().stream()
            .filter(t -> t.getSource().getId() == sm.getState().getId())
            .map(t -> t.getTrigger().getEvent())
            .distinct()
            .toList();
    }

    // === Private Methods ===

    private Order findOrderById(String orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private StateMachine<OrderStatus, OrderEvent> acquireStateMachine(
            String orderId, Order order) {

        StateMachine<OrderStatus, OrderEvent> sm =
            stateMachineFactory.getStateMachine(orderId);

        try {
            persister.restore(sm, orderId);
        } catch (Exception e) {
            log.debug("저장된 상태 없음, 초기화: orderId={}", orderId);
        }

        sm.getExtendedState().getVariables().put("order", order);

        return sm;
    }

    private boolean sendEvent(StateMachine<OrderStatus, OrderEvent> sm,
                             OrderEvent event) {
        var result = sm.sendEvent(
            Mono.just(MessageBuilder.withPayload(event).build())
        ).blockLast();

        return false;
    }

    private void persistAndSave(StateMachine<OrderStatus, OrderEvent> sm, Order order) {
        try {
            persister.persist(sm, order.getId());
            order.setStatus(sm.getState().getId());
            orderRepository.save(order);
        } catch (Exception e) {
            throw new StateMachinePersistException("상태 저장 실패", e);
        }
    }
}
```

### 3.3 테스트 코드

```java
@SpringBootTest
class OrderStateMachineTest {

    @Autowired
    private StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    private StateMachine<OrderStatus, OrderEvent> sm;
    private Order order;

    @BeforeEach
    void setUp() {
        sm = stateMachineFactory.getStateMachine("test-order-1");
        order = createTestOrder();
        sm.getExtendedState().getVariables().put("order", order);
        sm.start();
    }

    @Nested
    class CREATED_상태에서 {

        @Test
        void 성공_결제시_PAID로_전이() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.PAY);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        void 성공_취소시_CANCELLED로_전이() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        void 실패_배송_이벤트는_거부됨() {
            // given
            setInitialState(OrderStatus.CREATED);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.SHIP);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CREATED);
        }
    }

    @Nested
    class PAID_상태에서 {

        @Test
        void 성공_배송시작시_SHIPPED로_전이() {
            // given
            setInitialState(OrderStatus.PAID);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.SHIP);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        void 성공_취소시_CANCELLED로_전이_환불_처리() {
            // given
            setInitialState(OrderStatus.PAID);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Nested
    class SHIPPED_상태에서 {

        @Test
        void 성공_배송완료시_DELIVERED로_전이() {
            // given
            setInitialState(OrderStatus.SHIPPED);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.DELIVER);

            // then
            assertThat(accepted).isTrue();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.DELIVERED);
        }

        @Test
        void 실패_취소_이벤트는_거부됨_배송중_취소불가() {
            // given
            setInitialState(OrderStatus.SHIPPED);

            // when
            boolean accepted = sm.sendEvent(OrderEvent.CANCEL);

            // then
            assertThat(accepted).isFalse();
            assertThat(sm.getState().getId()).isEqualTo(OrderStatus.SHIPPED);
        }
    }

    @Nested
    class 전체_상태_전이_흐름 {

        @Test
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
    }

    // === Helper Methods ===

    private void setInitialState(OrderStatus status) {
        sm.getStateMachineAccessor()
            .doWithAllRegions(accessor -> accessor
                .resetStateMachine(new DefaultStateMachineContext<>(status, null, null, null)));
    }

    private Order createTestOrder() {
        return Order.builder()
            .id("test-order-1")
            .productId("product-1")
            .quantity(1)
            .totalAmount(new BigDecimal("10000"))
            .customerEmail("test@test.com")
            .build();
    }
}
```

---

## 4. Spring State Machine을 사용하면 좋은 상황

### 4.1 도입이 권장되는 상황

#### 1. 명확한 상태와 전이 규칙이 존재하는 경우

```
✅ 권장: 주문 상태 관리
- CREATED → PAID → SHIPPED → DELIVERED
- 취소, 반품 등 예외 플로우 포함

✅ 권장: 결제 상태 관리
- PENDING → PROCESSING → COMPLETED / FAILED

✅ 권장: 승인 워크플로우
- SUBMITTED → REVIEWING → APPROVED / REJECTED
```

#### 2. 상태 전이 조건이 복잡한 경우

```java
// 기존 방식: 복잡한 if-else 체인
public OrderStatus getStatus(Order order) {
    if (order.getCancelledAt() != null) return CANCELLED;
    if (order.getRefundedAt() != null) return REFUNDED;
    if (order.getDeliveredAt() != null) return DELIVERED;
    if (order.getShippedAt() != null) return SHIPPED;
    if (order.getPaidAt() != null) return PAID;
    return CREATED;
}
```
```
// SSM 방식: 선언적 정의
.withExternal()
    .source(CREATED).target(PAID).event(PAY)
    .guard(paymentValidGuard())
.withExternal()
    .source(PAID).target(SHIPPED).event(SHIP)
    .guard(stockAvailableGuard())
```

#### 3. 상태 전이에 따른 부수 효과(Side Effect)가 많은 경우

```
주문 결제 시 필요한 동작:
- 결제 처리 (PG 연동)
- 재고 차감
- 포인트 적립
- 알림 발송
- 로그 기록

→ Action으로 분리하여 체계적 관리 가능
```

#### 4. 상태 전이 이력 추적이 필요한 경우

```java
// Listener를 통한 자동 이력 관리
@Override
public void stateChanged(State from, State to) {
    transitionHistoryRepository.save(
        TransitionHistory.builder()
            .entityId(currentEntityId)
            .fromStatus(from.getId())
            .toStatus(to.getId())
            .event(currentEvent)
            .transitionAt(LocalDateTime.now())
            .build()
    );
}
```

#### 5. 비즈니스 규칙이 자주 변경되는 경우

```java
// 새로운 상태 추가가 용이
enum OrderStatus {
    // 기존
    CREATED, PAID, SHIPPED, DELIVERED, CANCELLED,
    // 신규 추가
    PENDING_APPROVAL,   // 승인 대기
    ON_HOLD             // 보류
}
```
```
// 새로운 전이 규칙 추가가 용이
transitions.withExternal()
    .source(CREATED).target(PENDING_APPROVAL).event(SUBMIT_FOR_APPROVAL)
    .guard(approvalRequiredGuard());
```

### 4.2 도입이 권장되지 않는 상황

#### 1. 상태가 단순한 경우 (2-3개)

```
⚠️ 비권장: 오버엔지니어링
- 활성/비활성: ACTIVE / INACTIVE
- 사용/미사용: ENABLED / DISABLED

→ 단순 boolean 플래그로 충분
```

#### 2. 상태가 저장값이 아닌 계산값인 경우

```java
// 계산 기반 상태: SSM 적용 부적합
public CampaignStatus calculateStatus(List<AdGroupStatus> statuses) {
    if (statuses.stream().anyMatch(s -> s == RUNNING)) {
        return CampaignStatus.RUNNING;
    }
    if (statuses.stream().allMatch(s -> s == EXPIRED)) {
        return CampaignStatus.EXPIRED;
    }
    // ...계산 로직
}
```
→ SSM보다는 Calculator 패턴이 적합

#### 3. 실시간 시간 기반 전이가 필요한 경우

```
⚠️ SSM 한계:
- "시작 시각에 자동으로 상태 전이"
- "종료 시각에 자동으로 만료 처리"

→ SSM 단독으로는 불가
→ Spring Scheduler + SSM Event 발송 조합 필요
```

### 4.3 도입 판단 체크리스트

| 항목 | 도입 권장 기준 |
|------|---------------|
| 상태 개수 | 4개 이상 |
| 상태 전이 규칙 | 5개 이상 |
| Guard 조건 | 복잡한 비즈니스 로직 포함 |
| Action | 3개 이상의 부수 효과 |
| 이력 추적 | 필요 |
| 상태 저장 | DB에 저장되는 실제 값 |

---

## 5. Spring State Machine의 장단점

### 5.1 장점

#### 1. 상태 전이 로직의 중앙화

```
Before: 분산된 상태 관리
├── StatusEnum (canCancel, canRefund)
├── StatusCalculator (getStatus)
├── TransitionValidator (isValidTransition)
├── Entity (updateStatus)
└── Service (processOrder)

After: 중앙화된 상태 관리
└── OrderStateMachineConfig
    ├── States
    ├── Events
    ├── Guards
    └── Actions
```

#### 2. 선언적이고 가독성 높은 코드

```
// 상태 전이 규칙이 한눈에 파악됨
transitions
    .withExternal().source(CREATED).target(PAID)
        .event(PAY).guard(paymentGuard())
    .withExternal().source(PAID).target(SHIPPED)
        .event(SHIP).action(shipAction())
    .withExternal().source(SHIPPED).target(DELIVERED)
        .event(DELIVER).action(deliverAction());
```

#### 3. 잘못된 상태 전이 방지

```
// SSM이 자동으로 유효하지 않은 전이를 거부
// CREATED에서 바로 SHIPPED로 전이 시도 → 거부됨
stateMachine.sendEvent(OrderEvent.SHIP);  // false 반환

// CANCELLED 상태에서 결제 시도 → 거부됨
stateMachine.sendEvent(OrderEvent.PAY);  // false 반환
```

#### 4. 테스트 용이성

```java
@Test
void 성공_PAID에서_SHIP시_SHIPPED로_전이() {
    setInitialState(OrderStatus.PAID);
    sm.sendEvent(OrderEvent.SHIP);
    assertThat(sm.getState().getId()).isEqualTo(OrderStatus.SHIPPED);
}

@Test
void 실패_CREATED에서_SHIP은_불가() {
    setInitialState(OrderStatus.CREATED);
    boolean accepted = sm.sendEvent(OrderEvent.SHIP);
    assertThat(accepted).isFalse();
    assertThat(sm.getState().getId()).isEqualTo(OrderStatus.CREATED);
}
```

#### 5. 확장성

```java
// 새로운 상태/이벤트 추가가 용이
enum OrderStatus {
    // 기존
    CREATED, PAID, SHIPPED, DELIVERED, CANCELLED,
    // 신규 추가
    PENDING_APPROVAL,
    PARTIALLY_SHIPPED
}
```

#### 6. 감사(Audit) 및 모니터링

```java
@Component
public class AuditListener extends StateMachineListenerAdapter {
    @Override
    public void stateChanged(State from, State to) {
        auditRepository.save(createAuditLog(from, to));
        metrics.incrementTransitionCount(from, to);
    }
}
```

### 5.2 단점

#### 1. 학습 곡선

```
필요한 학습 내용:
- State Machine 개념 이해
- Spring State Machine API 숙지
- Guard/Action 패턴
- 영속화 전략
- 테스트 방법

→ 팀 전체의 학습 시간 필요
```

#### 2. 복잡성 증가 (단순한 케이스에서)

```
// 단순한 활성/비활성 토글에 SSM 적용 시 오버엔지니어링
// Before: 1줄
entity.setActive(!entity.isActive());

// After: SSM 설정 + 수십 줄의 Config 코드
→ 단순 케이스는 SSM 적용 불필요
```

#### 3. 디버깅 어려움

```
문제 상황:
- "왜 이벤트가 거부되었는가?"
- "Guard 조건 중 어느 것이 false였는가?"
- "Action이 어떤 순서로 실행되었는가?"

해결책:
- 상세한 Listener 로깅
- Debug 레벨 로그 활성화
- 테스트 커버리지 확보
```

#### 4. 영속화 관리의 복잡성

```java
// 분산 환경에서의 상태 동기화 이슈
// 여러 서버에서 같은 엔티티의 상태 머신에 접근 시 충돌 가능

// 해결책: Redis 기반 영속화 + 분산 락
@Transactional
public void processOrder(String orderId) {
    Lock lock = lockService.acquire("order:" + orderId);
    try {
        // 상태 머신 처리
    } finally {
        lock.release();
    }
}
```

#### 5. 트랜잭션 관리 주의

```java
// Action 내에서 DB 작업 시 트랜잭션 범위 주의
@Bean
public Action<OrderStatus, OrderEvent> processPaymentAction() {
    return context -> {
        // 이 시점에서 트랜잭션이 없을 수 있음
        // Service 레이어에서 @Transactional 적용 필요
        order.setPaidAt(LocalDateTime.now());
    };
}
```

### 5.3 장단점 요약 표

| 관점 | 장점 | 단점 |
|------|------|------|
| **코드 품질** | 선언적, 가독성 향상 | 단순 케이스에서 과도함 |
| **유지보수** | 규칙 중앙화, 변경 용이 | 디버깅 어려움 |
| **안정성** | 잘못된 전이 방지 | 영속화/트랜잭션 복잡 |
| **테스트** | 상태 전이 테스트 용이 | 통합 테스트 필요 |
| **팀 역량** | - | 학습 곡선 존재 |
| **확장성** | 상태/전이 추가 용이 | 초기 설정 비용 |

---

## 6. 정리 요약

### 6.1 Spring State Machine 핵심 개념

```
┌─────────────────────────────────────────────────────────┐
│             Spring State Machine 핵심 구성                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  State (상태)                                            │
│  └─ CREATED, PAID, SHIPPED, DELIVERED, CANCELLED, ...   │
│                                                         │
│  Event (이벤트)                                           │
│  └─ PAY, SHIP, DELIVER, CANCEL, RETURN, REFUND, ...     │
│                                                         │
│  Transition (전이)                                       │
│  └─ CREATED → PAID (결제)                                │
│  └─ PAID → SHIPPED (배송 시작)                            │
│                                                         │
│  Guard (가드)                                            │
│  └─ 결제 가능 여부 검증                                     │
│  └─ 재고 확인                                             │
│                                                        │
│  Action (액션)                                          │
│  └─ 결제 처리 (PG 연동)                                    │
│  └─ 재고 차감                                             │
│  └─ 알림 발송                                             │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 6.2 도입 판단 기준

| 상황 | 판단 |
|------|------|
| 상태가 4개 이상 + 복잡한 전이 규칙 | ✅ 도입 권장 |
| 상태 전이 시 다수의 부수 효과 | ✅ 도입 권장 |
| 계산 기반 상태 | ❌ 부적합 |
| 단순 2-3개 상태 | ❌ 불필요 |
| 시간 기반 자동 전이 필요 | ⚠️ 스케줄러 통합 필요 |

### 6.3 도입 시 권장 사항

```
1. 점진적 도입
   └─ 핵심 도메인에서 먼저 검증

2. 기존 로직과 병행 운영
   └─ SSM 결과와 기존 결과 비교 검증

3. 충분한 테스트 작성
   └─ 모든 상태 전이 경로에 대한 테스트

4. 영속화 전략 결정
   └─ Redis / JPA 선택

5. 모니터링 설정
   └─ Listener를 통한 상태 전이 로깅
```

### 6.4 대안 고려
SSM 도입 전에 먼저 고려할 수 있는 대안 (디자인 패턴 적용)
```java
// 1. State Pattern (상태 패턴 적용)  
interface OrderState {  
    void pay(Order order);    
  void cancel(Order order);
}  
class PendingState implements OrderState {  
    @Override public void pay(Order order) {        // 결제 처리 후 상태 변경  
        order.setState(new PaidState());    
    }
}  
// 2. 상태 전이 규칙 명시화
enum OrderTransition {
    PENDING_TO_PAID(PENDING, PAID, "결제"),  
    PAID_TO_SHIPPED(PAID, SHIPPED, "배송시작");  
    
    public boolean isValid(OrderStatus current, OrderStatus target) 
    {        
        return this.source == current && this.target == target;    
    }
}  
// 3. Validator 클래스 분리
class OrderStateValidator {  
    public boolean canTransition(OrderStatus from, OrderStatus to)
    {
        return ALLOWED_TRANSITIONS.contains(Pair.of(from, to));
    }
}  
```

### 6.5 결론

Spring State Machine은 **복잡한 상태 관리가 필요한 도메인**에서 강력한 도구입니다.

**적합한 경우:**
- 다수의 상태 + 복잡한 전이 규칙
- 다양한 Guard 조건
- 다수의 Action (부수 효과)

**권장 접근법:**
1. 도메인의 상태 관리 복잡도 평가
2. 단순 if-else로 충분한지 검토
3. SSM 도입 시 핵심 도메인에서 파일럿 적용
4. 검증 후 확대 적용

---

## 참고 자료

- [Spring State Machine 심화 가이드](./spring-state-machine-advanced.md) - 동시성, 마이그레이션, 모니터링, FAQ
- [Spring State Machine 프로젝트 도입 검토](./spring-state-machine-adoption.md) - 도입 분석
- [Spring State Machine 공식 문서](https://docs.spring.io/spring-statemachine/docs/current/reference/)
- [Spring State Machine GitHub](https://github.com/spring-projects/spring-statemachine)
- [Baeldung - Spring State Machine Guide](https://www.baeldung.com/spring-state-machine)
