# 광고그룹 상태 관리 - 이벤트 기반 아키텍처 + SSM 도입 계획

> 본 문서는 광고그룹(AdGroup) 상태 관리를 **계산 기반 모델**에서 **이벤트 기반 모델**로 전환하고, Spring State Machine(SSM)을 적용하기 위한 구현 계획서입니다.

---

## 목차

1. [현황 분석](#1-현황-분석)
2. [목표 아키텍처](#2-목표-아키텍처)
3. [상태 및 이벤트 재정의](#3-상태-및-이벤트-재정의)
4. [인프라 구성](#4-인프라-구성)
5. [SSM 구현 설계](#5-ssm-구현-설계)
6. [마이그레이션 전략](#6-마이그레이션-전략)
7. [예상 효과](#7-예상-효과)

---

## 1. 현황 분석

### 1.1 현재 상태 관리 방식

현재 광고그룹 상태는 **조회 시점에 계산**되는 방식입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                    현재: 계산 기반 상태 모델                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   조회 요청                                                  │
│      │                                                      │
│      ▼                                                      │
│   AdGroupStatusCalculator.getStatus()                       │
│      │                                                      │
│      ├── if (deletedAt != null) → UNKNOWN                   │
│      ├── if (now > endAt) → EXPIRED                         │
│      ├── if (now >= startAt && now <= endAt) → RUNNING      │
│      ├── if (readyConfirmedAt != null) → READY_CONFIRM      │
│      ├── if (readyAt != null) → READY                       │
│      └── else → DRAFT                                       │
│                                                             │
│   결과: 매번 계산, 상태 저장 안 함 (또는 캐시 용도)            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 현재 방식의 문제점

| 문제점 | 설명 |
|--------|------|
| **상태 비저장** | 상태가 DB에 명시적으로 저장되지 않음 |
| **이력 추적 불가** | 상태 변경 시점/사유 기록 불가 |
| **조회 비용** | 매 조회마다 계산 로직 실행 |
| **분산 환경 불일치** | 서버 시간 차이로 인한 상태 불일치 가능 |
| **비즈니스 로직 분산** | 상태 판단 로직이 Calculator, Decider 등에 분산 |
| **테스트 어려움** | 시간 기반 로직 테스트 시 시간 모킹 필요 |

### 1.3 관련 기존 코드

| 클래스 | 역할 |
|--------|------|
| `AdGroupStatusCalculator` | 상태 계산 (5단계 if-else) |
| `AdGroupTransmissionCandidateDecider` | 송출 후보 판단 (시작/종료 조건) |
| `AdGroupStatus` | 상태 Enum |

---

## 2. 목표 아키텍처

### 2.1 이벤트 기반 상태 모델

```
┌─────────────────────────────────────────────────────────────────────┐
│                   목표: 이벤트 기반 상태 모델                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    Event Sources                             │   │
│   │                                                              │   │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │   │
│   │  │   사용자     │  │   스케줄러   │  │   외부 시스템    │   │   │
│   │  │   API 호출   │  │   (지연 큐)  │  │   (Kafka)        │   │   │
│   │  │              │  │              │  │                  │   │   │
│   │  │ - 승인       │  │ - 시작 시각  │  │ - 연동 이벤트    │   │   │
│   │  │ - 중지       │  │ - 종료 시각  │  │                  │   │   │
│   │  │ - 재개       │  │ - 일별 시작  │  │                  │   │   │
│   │  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘   │   │
│   └─────────┼─────────────────┼───────────────────┼─────────────┘   │
│             │                 │                   │                 │
│             ▼                 ▼                   ▼                 │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                   Event Bus (Redis / Kafka)                  │   │
│   │                                                              │   │
│   │   즉시 이벤트              지연 이벤트                        │   │
│   │   - APPROVE               - START (at startAt)              │   │
│   │   - PAUSE                 - EXPIRE (at endAt)               │   │
│   │   - RESUME                - DAILY_START (at dailyStartTime) │   │
│   │   - DELETE                - DAILY_END (at dailyEndTime)     │   │
│   └─────────────────────────────┬───────────────────────────────┘   │
│                                 │                                   │
│                                 ▼                                   │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                  State Machine (SSM)                         │   │
│   │                                                              │   │
│   │   ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐  │   │
│   │   │  Guard  │ →  │ Action  │ →  │ 상태    │ →  │ Domain  │  │   │
│   │   │  검증   │    │ 부수효과│    │ 저장    │    │ Event   │  │   │
│   │   └─────────┘    └─────────┘    └─────────┘    └─────────┘  │   │
│   │                                                              │   │
│   │   - 전이 조건 검증          - Redis 메타데이터 갱신           │   │
│   │   - 비즈니스 규칙           - 알림 발송                       │   │
│   │                             - 이력 기록                       │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                 │                                   │
│                                 ▼                                   │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                   Database (status 컬럼)                     │   │
│   │                                                              │   │
│   │   상태가 명시적으로 저장됨 → 조회 시 계산 불필요              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 핵심 변경 사항

| 항목 | 현재 | 변경 후 |
|------|------|--------|
| **상태 저장** | 계산 결과 (캐시) | DB 컬럼에 명시적 저장 |
| **상태 변경** | 시간 경과로 자동 변경 | 이벤트 발생 시에만 변경 |
| **시간 기반 전이** | 조회 시 계산 | 지연 큐가 이벤트 발행 |
| **비즈니스 로직** | Calculator에 분산 | SSM Guard/Action에 집중 |
| **이력 관리** | 없음 | SSM Listener로 자동 기록 |

---

## 3. 상태 및 이벤트 재정의

### 3.1 상태 (State) 정의

```java
public enum AdGroupStatus {

    // === 준비 단계 ===
    DRAFT,          // 임시저장 (소재, 인벤토리 미설정)
    READY,          // 준비완료 (소재, 인벤토리 설정됨, 승인 대기)
    APPROVED,       // 승인완료 (송출 대기)

    // === 송출 단계 ===
    RUNNING,        // 송출중
    PAUSED,         // 일시중지 (수동)
    PENDING,        // 일별 대기 (일별 송출 시간 외)

    // === 종료 단계 ===
    EXPIRED,        // 만료 (종료 시각 도래)
    DELETED         // 삭제됨
}
```

### 3.2 이벤트 (Event) 정의

```java
public enum AdGroupEvent {

    // === 사용자 액션 (즉시 처리) ===
    COMPLETE_SETUP,     // 설정 완료 (DRAFT → READY)
    APPROVE,            // 승인 (READY → APPROVED)
    REJECT,             // 반려 (READY → DRAFT)
    PAUSE,              // 일시중지 (RUNNING → PAUSED)
    RESUME,             // 재개 (PAUSED → RUNNING)
    DELETE,             // 삭제 (* → DELETED)

    // === 시간 기반 (지연 큐에서 발행) ===
    START,              // 송출 시작 (APPROVED → RUNNING)
    EXPIRE,             // 만료 (RUNNING/PAUSED → EXPIRED)

    // === 일별 송출 (지연 큐에서 발행) ===
    DAILY_START,        // 일별 송출 시작 (PENDING → RUNNING)
    DAILY_END           // 일별 송출 종료 (RUNNING → PENDING)
}
```

### 3.3 상태 전이 다이어그램

```
┌─────────────────────────────────────────────────────────────────────┐
│                      광고그룹 상태 전이 다이어그램                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│                         ┌──────────┐                                │
│                         │  DRAFT   │ ◀─────────────────┐            │
│                         │ (임시저장)│                   │            │
│                         └────┬─────┘                   │ REJECT     │
│                              │ COMPLETE_SETUP          │            │
│                              ▼                         │            │
│                         ┌──────────┐                   │            │
│                         │  READY   │ ──────────────────┘            │
│                         │ (준비완료)│                                │
│                         └────┬─────┘                                │
│                              │ APPROVE                              │
│                              ▼                                      │
│                         ┌──────────┐                                │
│                         │ APPROVED │                                │
│                         │ (승인완료)│                                │
│                         └────┬─────┘                                │
│                              │ START (지연 큐)                       │
│                              ▼                                      │
│     ┌────────────────── ┌──────────┐ ──────────────────┐            │
│     │                   │ RUNNING  │                   │            │
│     │        ┌─────────▶│ (송출중) │◀─────────┐        │            │
│     │        │          └────┬─────┘          │        │            │
│     │  RESUME│               │                │DAILY   │            │
│     │        │               │ PAUSE          │_START  │            │
│     │        │               ▼                │        │            │
│     │        │          ┌──────────┐          │        │            │
│     │        └──────────│  PAUSED  │          │        │            │
│     │                   │(일시중지)│          │        │            │
│     │                   └────┬─────┘          │        │            │
│     │                        │                │        │            │
│     │  DAILY_END             │                │        │            │
│     │        │               │                │        │            │
│     │        ▼               │                │        │            │
│     │   ┌──────────┐         │                │        │            │
│     └──▶│ PENDING  │◀────────┘                │        │            │
│         │(일별대기)│──────────────────────────┘        │            │
│         └────┬─────┘                                   │            │
│              │                                         │            │
│              │ EXPIRE (지연 큐)                         │ EXPIRE     │
│              ▼                                         │            │
│         ┌──────────┐                                   │            │
│         │ EXPIRED  │◀──────────────────────────────────┘            │
│         │  (만료)  │                                                │
│         └──────────┘                                                │
│                                                                     │
│                                                                     │
│         ┌──────────┐                                                │
│         │ DELETED  │ ◀── DELETE (모든 상태에서 가능, 종료 상태 제외) │
│         │  (삭제)  │                                                │
│         └──────────┘                                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.4 상태 전이 매트릭스

| From \ To | DRAFT | READY | APPROVED | RUNNING | PAUSED | PENDING | EXPIRED | DELETED |
|-----------|:-----:|:-----:|:--------:|:-------:|:------:|:-------:|:-------:|:-------:|
| DRAFT | - | COMPLETE_SETUP | - | - | - | - | - | DELETE |
| READY | REJECT | - | APPROVE | - | - | - | - | DELETE |
| APPROVED | - | - | - | START | - | - | - | DELETE |
| RUNNING | - | - | - | - | PAUSE | DAILY_END | EXPIRE | DELETE |
| PAUSED | - | - | - | RESUME | - | - | EXPIRE | DELETE |
| PENDING | - | - | - | DAILY_START | - | - | EXPIRE | DELETE |
| EXPIRED | - | - | - | - | - | - | - | - |
| DELETED | - | - | - | - | - | - | - | - |

---

## 4. 인프라 구성

### 4.1 지연 이벤트 시스템 (Redis Sorted Set)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    지연 이벤트 시스템 구조                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   [광고그룹 생성/수정]                                               │
│          │                                                          │
│          ▼                                                          │
│   ┌─────────────────────────────────────────┐                       │
│   │      DelayedEventScheduler              │                       │
│   │                                         │                       │
│   │  scheduleEvent(adGroupId, event, time)  │                       │
│   └─────────────────────────────────────────┘                       │
│          │                                                          │
│          ▼                                                          │
│   ┌─────────────────────────────────────────┐                       │
│   │      Redis Sorted Set                   │                       │
│   │                                         │                       │
│   │  Key: adgroup:delayed:events            │                       │
│   │                                         │                       │
│   │  ┌─────────────────────────────────┐    │                       │
│   │  │ Score (Unix Timestamp)          │    │                       │
│   │  │ 1709251200 → {AG001, START}     │    │                       │
│   │  │ 1709337600 → {AG001, EXPIRE}    │    │                       │
│   │  │ 1709258400 → {AG002, DAILY_END} │    │                       │
│   │  └─────────────────────────────────┘    │                       │
│   └─────────────────────────────────────────┘                       │
│          │                                                          │
│          │  (매초 폴링)                                              │
│          ▼                                                          │
│   ┌─────────────────────────────────────────┐                       │
│   │      DelayedEventProcessor              │                       │
│   │      (@Scheduled, fixedRate=1000)       │                       │
│   │                                         │                       │
│   │  1. ZRANGEBYSCORE로 현재 시각 이전 조회  │                       │
│   │  2. 이벤트 발행                         │                       │
│   │  3. ZREM으로 처리된 이벤트 삭제          │                       │
│   └─────────────────────────────────────────┘                       │
│          │                                                          │
│          ▼                                                          │
│   ┌─────────────────────────────────────────┐                       │
│   │      AdGroupStateMachineService         │                       │
│   │                                         │                       │
│   │  sendEvent(adGroupId, event)            │                       │
│   └─────────────────────────────────────────┘                       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 이벤트 예약 시점

| 시점 | 예약할 이벤트 |
|------|--------------|
| 광고그룹 승인 시 | `START` (at startAt), `EXPIRE` (at endAt) |
| 일별 송출 설정 시 | `DAILY_START` (at dailyStartTime), `DAILY_END` (at dailyEndTime) |
| 일정 변경 시 | 기존 이벤트 취소 후 재예약 |
| 삭제/만료 시 | 예약된 이벤트 모두 취소 |

### 4.3 Redis Key 설계

| Key | Type | 용도 |
|-----|------|------|
| `adgroup:delayed:events` | Sorted Set | 지연 이벤트 저장 (score=실행시각) |
| `adgroup:event:lock:{adGroupId}` | String | 분산 락 (이벤트 중복 처리 방지) |
| `adgroup:scheduled:{adGroupId}` | Set | 광고그룹별 예약된 이벤트 ID 목록 |

---

## 5. SSM 구현 설계

### 5.1 패키지 구조

```
com.torder.adadmin.domain.adgroup
├── statemachine/
│   ├── config/
│   │   └── AdGroupStateMachineConfig.java      # SSM 설정
│   ├── guard/
│   │   └── AdGroupGuards.java                  # 전이 조건 검증
│   ├── action/
│   │   └── AdGroupActions.java                 # 부수 효과 실행
│   ├── listener/
│   │   └── AdGroupStateMachineListener.java    # 이벤트/이력 기록
│   └── service/
│       └── AdGroupStateMachineService.java     # SSM 서비스
├── event/
│   ├── scheduler/
│   │   └── DelayedEventScheduler.java          # 지연 이벤트 예약
│   ├── processor/
│   │   └── DelayedEventProcessor.java          # 지연 이벤트 처리
│   └── dto/
│       └── AdGroupDelayedEvent.java            # 이벤트 DTO
```

### 5.2 Guard 정의

| Guard | 용도 | 검증 내용 |
|-------|------|----------|
| `setupCompleteGuard` | DRAFT → READY | 소재, 인벤토리 설정 여부 |
| `approvalGuard` | READY → APPROVED | 승인 권한, 캠페인 상태 |
| `startableGuard` | APPROVED → RUNNING | 현재 시각 ≥ startAt |
| `expirableGuard` | * → EXPIRED | 현재 시각 ≥ endAt |
| `pausableGuard` | RUNNING → PAUSED | 이미 중지 상태 아닌지 |
| `resumableGuard` | PAUSED → RUNNING | 만료되지 않았는지 |
| `deletableGuard` | * → DELETED | 종료 상태가 아닌지 |

### 5.3 Action 정의

| Action | 실행 시점 | 동작 |
|--------|----------|------|
| `updateStatusAction` | 모든 전이 | DB status 컬럼 업데이트 |
| `updateRedisMetadataAction` | RUNNING 진입/탈출 | Redis 송출 메타데이터 갱신 |
| `scheduleEventsAction` | APPROVED 진입 | START, EXPIRE 이벤트 예약 |
| `cancelEventsAction` | DELETED, EXPIRED 진입 | 예약된 이벤트 취소 |
| `sendNotificationAction` | 상태 변경 시 | 알림 발송 |
| `recordHistoryAction` | 모든 전이 | 상태 변경 이력 기록 |

### 5.4 SSM Config 예시

```java
@Configuration
@EnableStateMachineFactory(name = "adGroupStateMachineFactory")
@RequiredArgsConstructor
public class AdGroupStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<AdGroupStatus, AdGroupEvent> {

    private final AdGroupGuards guards;
    private final AdGroupActions actions;

    @Override
    public void configure(StateMachineStateConfigurer<AdGroupStatus, AdGroupEvent> states)
            throws Exception {
        states
            .withStates()
            .initial(AdGroupStatus.DRAFT)
            .states(EnumSet.allOf(AdGroupStatus.class))
            .end(AdGroupStatus.EXPIRED)
            .end(AdGroupStatus.DELETED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<AdGroupStatus, AdGroupEvent> transitions)
            throws Exception {
        transitions
            // DRAFT → READY
            .withExternal()
                .source(AdGroupStatus.DRAFT)
                .target(AdGroupStatus.READY)
                .event(AdGroupEvent.COMPLETE_SETUP)
                .guard(guards.setupCompleteGuard())
                .action(actions.updateStatusAction())
                .and()

            // READY → APPROVED
            .withExternal()
                .source(AdGroupStatus.READY)
                .target(AdGroupStatus.APPROVED)
                .event(AdGroupEvent.APPROVE)
                .guard(guards.approvalGuard())
                .action(actions.updateStatusAction())
                .action(actions.scheduleEventsAction())  // START, EXPIRE 예약
                .and()

            // APPROVED → RUNNING (지연 큐에서 START 이벤트 발행)
            .withExternal()
                .source(AdGroupStatus.APPROVED)
                .target(AdGroupStatus.RUNNING)
                .event(AdGroupEvent.START)
                .guard(guards.startableGuard())
                .action(actions.updateStatusAction())
                .action(actions.updateRedisMetadataAction())
                .action(actions.sendNotificationAction())
                .and()

            // RUNNING → EXPIRED (지연 큐에서 EXPIRE 이벤트 발행)
            .withExternal()
                .source(AdGroupStatus.RUNNING)
                .target(AdGroupStatus.EXPIRED)
                .event(AdGroupEvent.EXPIRE)
                .guard(guards.expirableGuard())
                .action(actions.updateStatusAction())
                .action(actions.updateRedisMetadataAction())
                .action(actions.cancelEventsAction())
                .action(actions.sendNotificationAction());

            // ... 기타 전이 정의
    }
}
```

---

## 6. 마이그레이션 전략

### 6.1 단계별 전환 계획

```
┌─────────────────────────────────────────────────────────────────────┐
│                        마이그레이션 단계                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Phase 1: 인프라 구축 (1주)                                         │
│  ├── 지연 이벤트 시스템 구현 (Redis Sorted Set)                      │
│  ├── SSM 설정 및 Guard/Action 구현                                  │
│  └── 단위 테스트 작성                                               │
│                                                                     │
│  Phase 2: 병행 운영 (2주)                                           │
│  ├── 기존 Calculator와 SSM 병행                                     │
│  ├── 신규 광고그룹만 SSM 적용 (Feature Flag)                        │
│  ├── 상태 불일치 모니터링                                           │
│  └── 통합 테스트 및 검증                                            │
│                                                                     │
│  Phase 3: 기존 데이터 마이그레이션 (1주)                             │
│  ├── 기존 광고그룹 상태 계산 후 DB 저장                              │
│  ├── 진행 중인 광고그룹에 대해 지연 이벤트 예약                       │
│  └── Calculator 제거                                                │
│                                                                     │
│  Phase 4: 안정화 (1주)                                              │
│  ├── 모니터링 및 알림 설정                                          │
│  └── 문서화 및 팀 교육                                              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 기존 데이터 마이그레이션

```java
@Component
@RequiredArgsConstructor
public class AdGroupStateMigration {

    private final AdGroupRepository repository;
    private final AdGroupStatusCalculator calculator;  // 기존 계산기
    private final DelayedEventScheduler eventScheduler;

    @Transactional
    public void migrateAll() {
        List<AdGroup> adGroups = repository.findAllActive();

        for (AdGroup adGroup : adGroups) {
            // 1. 현재 상태 계산
            AdGroupStatus calculatedStatus = calculator.getStatus(adGroup);

            // 2. DB에 상태 저장
            adGroup.updateStatus(calculatedStatus);
            repository.save(adGroup);

            // 3. 진행 중인 광고그룹은 지연 이벤트 예약
            if (calculatedStatus == AdGroupStatus.RUNNING ||
                calculatedStatus == AdGroupStatus.APPROVED) {
                scheduleRemainingEvents(adGroup);
            }
        }
    }

    private void scheduleRemainingEvents(AdGroup adGroup) {
        LocalDateTime now = LocalDateTime.now();

        // 아직 시작 전이면 START 이벤트 예약
        if (adGroup.getStartAt().isAfter(now)) {
            eventScheduler.scheduleEvent(
                adGroup.getId(),
                AdGroupEvent.START,
                adGroup.getStartAt()
            );
        }

        // 종료 시각이 남았으면 EXPIRE 이벤트 예약
        if (adGroup.getEndAt().isAfter(now)) {
            eventScheduler.scheduleEvent(
                adGroup.getId(),
                AdGroupEvent.EXPIRE,
                adGroup.getEndAt()
            );
        }
    }
}
```

### 6.3 Feature Flag를 통한 점진적 적용

```java
@Service
@RequiredArgsConstructor
public class AdGroupStatusResolver {

    private final AdGroupStatusCalculator calculator;
    private final AdGroupStateMachineService stateMachineService;
    private final FeatureFlagService featureFlags;

    public AdGroupStatus getStatus(AdGroup adGroup) {
        if (featureFlags.isEnabled("USE_SSM_STATUS", adGroup.getId())) {
            // 새로운 방식: DB에 저장된 상태 반환
            return adGroup.getStatus();
        } else {
            // 기존 방식: 계산
            return calculator.getStatus(adGroup);
        }
    }
}
```

---

## 7. 예상 효과

### 7.1 정량적 효과

| 지표 | 현재 | 예상 |
|------|------|------|
| **상태 조회 응답 시간** | 계산 로직 실행 | DB 컬럼 조회 (향상) |
| **상태 이력 추적** | 불가 | 전체 이력 기록 |
| **테스트 커버리지** | 시간 모킹 필요 | 이벤트 기반 테스트 (용이) |
| **코드 복잡도** | Calculator 5단계 if-else | SSM 선언적 설정 |

### 7.2 정성적 효과

| 관점 | 효과 |
|------|------|
| **유지보수** | 상태 전이 규칙이 한 곳(SSM Config)에 집중 |
| **확장성** | 새로운 상태/이벤트 추가 용이 |
| **디버깅** | 상태 변경 이력으로 문제 추적 가능 |
| **일관성** | 분산 환경에서 상태 일관성 보장 |
| **가시성** | 상태 전이 다이어그램으로 비즈니스 로직 문서화 |

### 7.3 리스크 및 대응

| 리스크 | 대응 방안 |
|--------|----------|
| 지연 큐 장애 | Redis 고가용성 구성, 복구 프로세스 구현 |
| 이벤트 중복 처리 | Guard에서 멱등성 검증, 분산 락 적용 |
| 마이그레이션 실패 | 롤백 계획 수립, Feature Flag로 점진적 적용 |
| 성능 저하 | 이벤트 배치 처리, 모니터링 강화 |

---

## 참고 자료

- [Spring State Machine 기술 가이드](./spring-state-machine-guide.md)
- [Spring State Machine 심화 가이드](./spring-state-machine-advanced.md)
- [Spring State Machine 프로젝트 도입 검토](./spring-state-machine-adoption.md)
