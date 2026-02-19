# Spring State Machine Demo

Spring State Machine(SSM)을 활용한 주문 상태 관리 데모 프로젝트입니다.

## 프로젝트 구조

```
ssm-demo/
├── src/main/java/com/example/ssmdemo/
│   ├── domain/order/
│   │   ├── entity/Order.java          # 주문 엔티티
│   │   ├── enums/
│   │   │   ├── OrderStatus.java       # 주문 상태 Enum
│   │   │   └── OrderEvent.java        # 주문 이벤트 Enum
│   │   └── repository/OrderRepository.java
│   ├── statemachine/
│   │   ├── config/OrderStateMachineConfig.java  # SSM 설정
│   │   ├── guard/OrderGuards.java               # Guard (조건)
│   │   ├── action/OrderActions.java             # Action (부수효과)
│   │   └── listener/OrderStateMachineListener.java
│   ├── service/OrderService.java
│   ├── controller/OrderController.java
│   └── exception/
├── src/test/java/
│   ├── OrderStateMachineTest.java     # State Machine 단위 테스트
│   └── OrderServiceTest.java          # Service 통합 테스트
└── build.gradle
```

## 상태 전이 다이어그램

```
    ┌──────────┐
    │ CREATED  │ ─────────────────────────────────┐
    └────┬─────┘                                   │
         │ PAY                                     │ CANCEL
         ▼                                         ▼
    ┌──────────┐                             ┌──────────┐
    │   PAID   │ ────────────────────────────│ CANCELLED│
    └────┬─────┘  CANCEL (환불)              └──────────┘
         │ SHIP
         ▼
    ┌──────────┐
    │ SHIPPED  │  ← 이 상태에서는 취소 불가
    └────┬─────┘
         │ DELIVER
         ▼
    ┌──────────┐
    │ DELIVERED│
    └────┬─────┘
         │ RETURN
         ▼
    ┌──────────┐
    │ RETURNED │
    └──────────┘
```

## 실행 방법

### 1. 프로젝트 빌드

```bash
cd /Users/dhkim1522/project/ssm-demo
./gradlew build
```

### 2. 애플리케이션 실행

```bash
./gradlew bootRun
```

> **참고:** Redis 연결 없이도 동작합니다 (In-Memory 모드).

### 3. 테스트 실행

```bash
./gradlew test
```

### 4. API 통합 테스트 시나리오 실행

터미널 2개를 사용하여 실제 API를 통한 상태 전이를 시연할 수 있습니다.

**사전 요구사항:**
```bash
# jq 설치 (JSON 파싱용)
brew install jq  # macOS
```

**터미널 1 - 애플리케이션 실행:**
```bash
cd /Users/dhkim1522/project/ssm-demo
./gradlew bootRun
```

**터미널 2 - 시나리오 스크립트 실행:**
```bash
cd /Users/dhkim1522/project/ssm-demo
./test-scenarios.sh
```

**시나리오 스크립트 내용:**

| 시나리오 | 설명 | 예상 결과 |
|---------|------|----------|
| 1. 정상 주문 흐름 | CREATED → PAID → SHIPPED → DELIVERED | 성공 |
| 2. 잘못된 전이 시도 | CREATED에서 바로 SHIP | 에러 (결제 필요) |
| 3. 결제 후 취소 | CREATED → PAID → CANCELLED | 성공 (환불) |
| 4. 배송 중 취소 불가 | SHIPPED 상태에서 CANCEL | 에러 (취소 불가) |
| 5. 반품 처리 | DELIVERED → RETURNED | 성공 |

**예상 출력:**
```
==========================================
  Spring State Machine 상태 전이 시연
==========================================

===== 시나리오 1: 정상 주문 흐름 =====
CREATED → PAID → SHIPPED → DELIVERED

[1] 주문 생성 완료: ORD-A1B2C3D4 (상태: CREATED)
[2] 결제 처리 중...
    → 상태: PAID
[3] 배송 시작 중...
    → 상태: SHIPPED
[4] 배송 완료 처리 중...
    → 상태: DELIVERED
...
```

**애플리케이션 콘솔 로그 (터미널 1):**
```
┌──────────────────────────────────────────┐
│ [STATE] 상태 변경: CREATED → PAID         │
└──────────────────────────────────────────┘
============================================
[Action] 결제 처리 완료
  - 주문 ID: ORD-A1B2C3D4
  - 결제 ID: PAY-XXXXXXXX
============================================
```

### 5. 실패 시나리오 테스트 (예외 처리 검증)

잘못된 상태 전이 시도 시 서버에서 예외가 발생하는지 검증하는 스크립트입니다.

**터미널 2 - 실패 시나리오 스크립트 실행:**
```bash
cd /Users/dhkim1522/project/ssm-demo
./test-failure-scenarios.sh
```

**실패 테스트 시나리오:**

| 테스트 | 시도 | 예상 에러 |
|-------|------|----------|
| 1. 존재하지 않는 주문 조회 | GET /orders/XXX | NOT_FOUND (404) |
| 2. CREATED → SHIP | 결제 없이 배송 | INVALID_TRANSITION (400) |
| 3. CREATED → DELIVER | 결제/배송 없이 배송완료 | INVALID_TRANSITION (400) |
| 4. CREATED → RETURN | 배송완료 전 반품 | INVALID_TRANSITION (400) |
| 5. PAID → DELIVER | 배송 없이 배송완료 | INVALID_TRANSITION (400) |
| 6. SHIPPED → CANCEL | 배송 중 취소 | INVALID_TRANSITION (400) |
| 7. CANCELLED → PAY | 취소 후 결제 | INVALID_TRANSITION (400) |
| 8. DELIVERED → SHIP | 배송완료 후 재배송 | INVALID_TRANSITION (400) |
| 9. RETURNED → CANCEL | 반품 후 취소 | INVALID_TRANSITION (400) |

**예상 출력:**
```
==========================================
  Spring State Machine 실패 시나리오 테스트
==========================================

===== 테스트 1: 존재하지 않는 주문 조회 =====
GET /api/orders/NON_EXISTENT_ORDER

    ✓ PASS: 존재하지 않는 주문 조회 시 404 반환
    → 에러 코드: NOT_FOUND
    → 메시지: 주문을 찾을 수 없습니다: NON_EXISTENT_ORDER

...

==========================================
  테스트 결과 요약
==========================================

  통과: 9
  실패: 0

  ✓ 모든 실패 시나리오 테스트 통과!
==========================================
```

**서버 로그 (터미널 1):**
```
WARN  c.e.s.e.GlobalExceptionHandler : Order not found: 주문을 찾을 수 없습니다: NON_EXISTENT_ORDER
WARN  c.e.s.e.GlobalExceptionHandler : Invalid state transition: 이벤트 [SHIP]을(를) 처리할 수 없습니다...
```

## API 시연 가이드

### Step 1: 주문 생성

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "PROD-001",
    "quantity": 2,
    "amount": 50000,
    "customerEmail": "customer@example.com",
    "paymentMethod": "CARD"
  }'
```

응답:
```json
{
  "id": "ORD-A1B2C3D4",
  "status": "CREATED",
  "statusDescription": "주문 생성"
}
```

### Step 2: 결제 처리

```bash
curl -X POST http://localhost:8080/api/orders/{orderId}/pay
```

응답:
```json
{
  "id": "ORD-A1B2C3D4",
  "status": "PAID",
  "paymentId": "PAY-XXXXXXXX",
  "paidAt": "2025-02-19T10:30:00"
}
```

### Step 3: 배송 시작

```bash
curl -X POST http://localhost:8080/api/orders/{orderId}/ship
```

### Step 4: 배송 완료

```bash
curl -X POST http://localhost:8080/api/orders/{orderId}/deliver
```

### Step 5: 반품 처리 (선택)

```bash
curl -X POST http://localhost:8080/api/orders/{orderId}/return
```

### 가능한 이벤트 조회

현재 상태에서 가능한 이벤트를 조회할 수 있습니다:

```bash
curl http://localhost:8080/api/orders/{orderId}/available-events
```

응답 예시:
```json
{
  "orderId": "ORD-A1B2C3D4",
  "currentStatus": "PAID",
  "currentStatusDescription": "결제 완료",
  "availableEvents": [
    {"event": "SHIP", "description": "배송 시작"},
    {"event": "CANCEL", "description": "취소"}
  ]
}
```

## 시연 시나리오

### 시나리오 1: 정상 주문 흐름

```bash
# 1. 주문 생성
ORDER_ID=$(curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-001","quantity":1,"amount":30000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')

echo "주문 ID: $ORDER_ID"

# 2. 결제
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/pay

# 3. 배송 시작
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/ship

# 4. 배송 완료
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/deliver
```

### 시나리오 2: 주문 취소 (결제 전)

```bash
# 1. 주문 생성
ORDER_ID=$(curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-002","quantity":1,"amount":20000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')

# 2. 바로 취소
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/cancel
```

### 시나리오 3: 잘못된 전이 시도 (에러 케이스)

```bash
# 1. 주문 생성
ORDER_ID=$(curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-003","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')

# 2. 결제 없이 배송 시도 → 에러!
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/ship
# {"error":"INVALID_TRANSITION","message":"이벤트 [SHIP]을(를) 처리할 수 없습니다. 현재 상태: [CREATED]"}
```

### 시나리오 4: 배송 중 취소 시도 (Guard 검증)

```bash
# 1. 주문 생성 → 결제 → 배송 시작
ORDER_ID=$(curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-004","quantity":1,"amount":40000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')

curl -X POST http://localhost:8080/api/orders/$ORDER_ID/pay
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/ship

# 2. 배송 중 취소 시도 → 에러!
curl -X POST http://localhost:8080/api/orders/$ORDER_ID/cancel
# {"error":"INVALID_TRANSITION","message":"이벤트 [CANCEL]을(를) 처리할 수 없습니다. 현재 상태: [SHIPPED]"}
```

## 로그 확인

애플리케이션 실행 중 콘솔에서 상태 전이 로그를 확인할 수 있습니다:

```
┌──────────────────────────────────────────┐
│ [STATE] 상태 변경: CREATED → PAID         │
└──────────────────────────────────────────┘
============================================
[Action] 결제 처리 완료
  - 주문 ID: ORD-A1B2C3D4
  - 결제 ID: PAY-XXXXXXXX
  - 결제 금액: 50000
  - 결제 수단: CARD
============================================
```

## 핵심 코드 포인트

### 1. State Machine 설정 (OrderStateMachineConfig.java)

```java
@Override
public void configure(StateMachineTransitionConfigurer<OrderStatus, OrderEvent> transitions) {
    transitions
        .withExternal()
            .source(OrderStatus.CREATED)
            .target(OrderStatus.PAID)
            .event(OrderEvent.PAY)
            .guard(guards.paymentValidGuard())      // 조건 검증
            .action(actions.processPaymentAction()) // 결제 처리
            .action(actions.sendNotificationAction()) // 알림 발송
}
```

### 2. Guard - 조건 검증 (OrderGuards.java)

```java
public Guard<OrderStatus, OrderEvent> paymentValidGuard() {
    return context -> {
        Order order = getOrder(context);
        return order.getTotalAmount().compareTo(BigDecimal.ZERO) > 0
            && order.getPaymentMethod() != null;
    };
}
```

### 3. Action - 부수 효과 실행 (OrderActions.java)

```java
public Action<OrderStatus, OrderEvent> processPaymentAction() {
    return context -> {
        Order order = getOrder(context);
        String paymentId = generatePaymentId();
        order.markAsPaid(paymentId);
        log.info("결제 처리 완료: {}", paymentId);
    };
}
```

## 기술 스택

- Java 21
- Spring Boot 3.4.5
- Spring State Machine 4.0.0
- H2 Database (In-Memory)
- JUnit 5 + AssertJ

---

## 문서

본 프로젝트에는 Spring State Machine 학습 및 도입을 위한 문서가 포함되어 있습니다.

### 문서 목록

| 문서 | 목적 | 대상 |
|------|------|------|
| [기술 가이드](docs/spring-state-machine-guide.md) | SSM 핵심 개념 및 사용법 교육 | 팀 전체 |
| [도입 검토](docs/spring-state-machine-adoption.md) | 프로젝트 도입 여부 판단 | 의사결정자 |
| [심화 가이드](docs/spring-state-machine-advanced.md) | 동시성, 마이그레이션, 모니터링 | 구현 담당자 |
| [광고그룹 구현 계획](docs/spring-state-machine-adgroup-implementation.md) | 이벤트 기반 아키텍처 + SSM 구현 | 구현 담당자 |

### 문서별 상세 설명

#### 1. 기술 가이드 (`spring-state-machine-guide.md`)

Spring State Machine의 **기본 개념과 사용법**을 다룹니다.

- State Machine 핵심 개념 (State, Event, Transition, Guard, Action)
- 주문 도메인 기반 완전한 예제 코드
- SSM 적용이 적합한/부적합한 상황
- 장단점 분석

#### 2. 도입 검토 (`spring-state-machine-adoption.md`)

현재 프로젝트에 SSM **도입 여부를 판단**하기 위한 분석 문서입니다.

- 현재 아키텍처 분석 (AdGroupStatusCalculator 등)
- 도입 권장 도메인: 광고그룹(AdGroup)
- 현재 문제점 및 SSM 도입 시 기대 효과

#### 3. 심화 가이드 (`spring-state-machine-advanced.md`)

SSM을 **실제 프로덕션 환경에 적용**할 때 필요한 심화 주제입니다.

- 버전 호환성 (Spring Boot 3.4.5 + SSM 4.0.0)
- Redis 분산 락 기반 동시성 제어
- 4단계 마이그레이션 전략
- 롤백 전략 및 Prometheus/Grafana 모니터링
- FAQ 및 트러블슈팅

#### 4. 광고그룹 구현 계획 (`spring-state-machine-adgroup-implementation.md`)

광고그룹 상태 관리를 **계산 기반에서 이벤트 기반으로 전환**하고 SSM을 적용하기 위한 구현 계획서입니다.

- 현재 계산 기반 상태 모델의 문제점
- 이벤트 기반 아키텍처 목표 설계
- 상태 및 이벤트 재정의 (8개 상태, 10개 이벤트)
- Redis Sorted Set 기반 지연 큐 설계
- SSM Guard/Action 설계
- 단계별 마이그레이션 전략

### 학습 순서 권장

```
1. 기술 가이드        → SSM 기본 개념 이해
       ↓
2. 도입 검토          → 프로젝트 적용 가능성 판단
       ↓
3. 심화 가이드        → 실제 구현 시 고려사항 학습
       ↓
4. 광고그룹 구현 계획  → 실제 구현 계획 수립
```
