# Spring State Machine 심화 가이드

> 본 문서는 Spring State Machine 도입 시 필요한 심화 주제를 다룹니다.
> 기본 개념은 [Spring State Machine 기술 가이드](./spring-state-machine-guide.md)를 참조하세요.

---

## 목차

1. [버전 호환성 및 환경 설정](#1-버전-호환성-및-환경-설정)
2. [동시성 처리](#2-동시성-처리)
3. [기존 코드와 직접 비교](#3-기존-코드와-직접-비교)
4. [마이그레이션 전략](#4-마이그레이션-전략)
5. [롤백 전략](#5-롤백-전략)
6. [모니터링 연동](#6-모니터링-연동)
7. [FAQ](#7-faq)
8. [트러블슈팅](#8-트러블슈팅)

---

## 1. 버전 호환성 및 환경 설정

### 1.1 버전 매트릭스

| Spring Boot | Spring State Machine | Java | 비고 |
|-------------|---------------------|------|------|
| 3.4.x | 4.0.x | 21+ | **현재 프로젝트 권장** |
| 3.2.x ~ 3.3.x | 4.0.x | 17+ | 호환 |
| 2.7.x | 3.2.x | 11+ | Legacy |

### 1.2 의존성 설정

```gradle
// build.gradle
dependencies {
    // Spring State Machine Core (필수)
    implementation 'org.springframework.statemachine:spring-statemachine-core:4.0.0'

    // Redis 영속화 (권장 - 현재 프로젝트에서 Redis 사용 중)
    implementation 'org.springframework.statemachine:spring-statemachine-data-redis:4.0.0'

    // JPA 영속화 (대안)
    // implementation 'org.springframework.statemachine:spring-statemachine-data-jpa:4.0.0'

    // 테스트
    testImplementation 'org.springframework.statemachine:spring-statemachine-test:4.0.0'
}
```

### 1.3 Spring Boot 3.4.5 호환성 체크리스트

| 항목 | 상태 | 설명 |
|------|:----:|------|
| Jakarta EE 10 | ✅ | SSM 4.0은 Jakarta 네임스페이스 사용 |
| Spring Framework 6.x | ✅ | 완전 호환 |
| Reactive Streams | ✅ | `Mono`/`Flux` 기반 이벤트 처리 |
| Virtual Threads | ⚠️ | 테스트 필요 (Project Loom) |
| GraalVM Native | ⚠️ | 제한적 지원 |

### 1.4 설정 프로퍼티

```yaml
# application.yml
spring:
  statemachine:
    # 상태 머신 모니터링
    monitor:
      enabled: true

    # Redis 영속화 설정
    redis:
      namespace: ad-admin:statemachine

# 커스텀 설정 (필요 시)
ad-admin:
  statemachine:
    # 상태 전이 타임아웃 (ms)
    transition-timeout: 5000
    # 분산 락 타임아웃 (ms)
    lock-timeout: 10000
    # 재시도 횟수
    max-retries: 3
```

---

## 2. 동시성 처리

### 2.1 동시성 이슈 시나리오

```
┌─────────────────────────────────────────────────────────────────┐
│                    동시 요청 시나리오                             │
└─────────────────────────────────────────────────────────────────┘

  Server A                          Server B
     │                                 │
     │  manualOn(adGroupId=123)        │  manualOff(adGroupId=123)
     │         │                       │         │
     │         ↓                       │         ↓
     │  ┌─────────────┐                │  ┌─────────────┐
     │  │ Load SM     │                │  │ Load SM     │
     │  │ State=READY │                │  │ State=READY │
     │  └─────────────┘                │  └─────────────┘
     │         │                       │         │
     │         ↓                       │         ↓
     │  ┌─────────────┐                │  ┌─────────────┐
     │  │ Send Event  │                │  │ Send Event  │
     │  │ MANUAL_ON   │                │  │ MANUAL_OFF  │
     │  └─────────────┘                │  └─────────────┘
     │         │                       │         │
     │         ↓                       │         ↓
     │  ┌─────────────┐                │  ┌─────────────┐
     │  │ Save State  │                │  │ Save State  │  ← 충돌!
     │  │ READY_CONF  │                │  │ ???         │
     │  └─────────────┘                │  └─────────────┘
     │                                 │

문제: 두 서버가 동시에 같은 광고그룹의 상태를 변경하려고 함
```

### 2.2 Redis 분산 락 구현

```java
package com.torder.adadmin.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 분산 락 구현
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDistributedLock {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String LOCK_PREFIX = "lock:adgroup:";
    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 락 획득 시도
     */
    public LockContext tryLock(String adGroupId) {
        return tryLock(adGroupId, DEFAULT_LOCK_TIMEOUT, DEFAULT_WAIT_TIMEOUT);
    }

    public LockContext tryLock(String adGroupId, Duration lockTimeout, Duration waitTimeout) {
        String lockKey = LOCK_PREFIX + adGroupId;
        String lockValue = UUID.randomUUID().toString();
        long waitUntil = System.currentTimeMillis() + waitTimeout.toMillis();

        while (System.currentTimeMillis() < waitUntil) {
            Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, lockTimeout);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Lock acquired: adGroupId={}, lockValue={}", adGroupId, lockValue);
                return new LockContext(lockKey, lockValue, true);
            }

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warn("Failed to acquire lock: adGroupId={}", adGroupId);
        return new LockContext(lockKey, lockValue, false);
    }

    /**
     * 락 해제
     */
    public void unlock(LockContext context) {
        if (!context.isAcquired()) {
            return;
        }

        String currentValue = redisTemplate.opsForValue().get(context.getLockKey());

        // 자신이 획득한 락만 해제 (다른 프로세스가 획득한 락 보호)
        if (context.getLockValue().equals(currentValue)) {
            redisTemplate.delete(context.getLockKey());
            log.debug("Lock released: lockKey={}", context.getLockKey());
        } else {
            log.warn("Lock value mismatch, not releasing: lockKey={}", context.getLockKey());
        }
    }

    public record LockContext(String lockKey, String lockValue, boolean acquired) {
        public boolean isAcquired() {
            return acquired;
        }
        public String getLockKey() {
            return lockKey;
        }
        public String getLockValue() {
            return lockValue;
        }
    }
}
```

### 2.3 Service에서 분산 락 적용

```java
@Service
@RequiredArgsConstructor
public class AdGroupServiceImpl implements AdGroupService {

    private final RedisDistributedLock distributedLock;
    private final StateMachineFactory<AdGroupStatus, AdGroupEvent> stateMachineFactory;

    @Override
    @AdTransactional
    public void manualOn(String adGroupId) {
        // 1. 분산 락 획득
        LockContext lockContext = distributedLock.tryLock(adGroupId);

        if (!lockContext.isAcquired()) {
            throw new ConcurrentModificationException(
                "다른 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            // 2. 상태 머신 처리
            AdGroup adGroup = findAdGroupById(adGroupId);
            StateMachine<AdGroupStatus, AdGroupEvent> sm = acquireStateMachine(adGroupId, adGroup);

            boolean accepted = sendEvent(sm, AdGroupEvent.MANUAL_ON);

            if (!accepted) {
                throw new InvalidStateTransitionException(
                    String.format("현재 상태에서 수동 ON 불가: %s", adGroup.getStatus()));
            }

            // 3. 상태 저장
            persistAndSave(sm, adGroup);

        } finally {
            // 4. 락 해제 (항상 실행)
            distributedLock.unlock(lockContext);
        }
    }
}
```

### 2.4 동시성 테스트

```java
@SpringBootTest
class AdGroupConcurrencyTest {

    @Autowired
    private AdGroupService adGroupService;

    @Autowired
    private TestEntityFactory testEntityFactory;

    @Test
    void 성공_동시_요청시_하나만_처리되고_나머지는_대기_후_처리() throws Exception {
        // given
        AdGroup adGroup = testEntityFactory.createReadyAdGroup();
        entityManager.persist(adGroup);
        entityManager.flush();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        executor.submit(() -> {
            try {
                adGroupService.manualOn(adGroup.getId());
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                adGroupService.manualOff(adGroup.getId());
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        latch.await(10, TimeUnit.SECONDS);

        // then
        // 두 요청 모두 순차적으로 처리되거나, 상태 충돌로 하나가 실패
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);
    }
}
```

---

## 3. 기존 코드와 직접 비교

### 3.1 AdGroupStatusCalculator vs SSM Guard

#### 기존 코드: AdGroupStatusCalculator

```java
// src/main/java/.../utils/AdGroupStatusCalculator.java (현재 코드)

@UtilityClass
public class AdGroupStatusCalculator {

    public AdGroupStatus getStatus(AdGroupValidationDto dto) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        // 1단계: 종료 여부
        if (dto.getAdGroupEndAt() != null && now.isAfter(dto.getAdGroupEndAt())) {
            return AdGroupStatus.EXPIRED;
        }

        // 2단계: 준비 완료 여부
        boolean ready = isReady(dto);
        if (!ready) {
            return AdGroupStatus.DRAFT;
        }

        // 3단계: 송출 중 여부
        if (dto.isRunningStatus()) {
            return AdGroupStatus.RUNNING;
        }

        // 4단계: 준비 확정 여부
        if (isReadyConfirmStatus(dto, now)) {
            return AdGroupStatus.READY_CONFIRM;
        }

        // 5단계: 일시 중지 여부
        if (dto.getTransmittedAt() != null) {
            return AdGroupStatus.PENDING;
        }

        return AdGroupStatus.READY;
    }

    public boolean isReady(AdGroupValidationDto dto) {
        if (!hasRequiredCommonFields(dto)) return false;

        AdGroupType adGroupType = dto.getAdGroupType();
        CreativeType creativeType = dto.getCreativeType();

        Optional<CreativeRule> ruleOpt = CreativeRuleRegistry.findRule(adGroupType, creativeType);
        if (ruleOpt.isEmpty()) return false;

        CreativeRule rule = ruleOpt.get();
        return validateResources(dto, rule) &&
               validateTexts(dto, rule) &&
               validateTerms(dto);
    }

    // ... 기타 private 메서드들
}
```

#### SSM 방식: Guard로 분리

```java
// SSM 방식: 각 조건을 독립적인 Guard로 분리

/**
 * 필수값 충족 Guard (기존 isReady() 로직)
 */
@Component
@RequiredArgsConstructor
public class RequirementFulfilledGuard implements Guard<AdGroupStatus, AdGroupEvent> {

    @Override
    public boolean evaluate(StateContext<AdGroupStatus, AdGroupEvent> context) {
        AdGroup adGroup = getAdGroup(context);

        // 기존 AdGroupStatusCalculator.isReady() 로직을 여기로 이전
        if (!hasRequiredCommonFields(adGroup)) {
            log.debug("Required fields not filled: adGroupId={}", adGroup.getId());
            return false;
        }

        if (!validateResources(adGroup)) {
            log.debug("Resources validation failed: adGroupId={}", adGroup.getId());
            return false;
        }

        if (!validateTexts(adGroup)) {
            log.debug("Texts validation failed: adGroupId={}", adGroup.getId());
            return false;
        }

        if (!validateTerms(adGroup)) {
            log.debug("Terms validation failed: adGroupId={}", adGroup.getId());
            return false;
        }

        return true;
    }
}

/**
 * 종료 시각 Guard (기존 EXPIRED 판정 로직)
 */
@Component
public class ExpirationGuard implements Guard<AdGroupStatus, AdGroupEvent> {

    @Override
    public boolean evaluate(StateContext<AdGroupStatus, AdGroupEvent> context) {
        AdGroup adGroup = getAdGroup(context);
        LocalDateTime now = LocalDateTime.now();

        return adGroup.getEndAt() != null && now.isAfter(adGroup.getEndAt());
    }
}
```

### 3.2 AdGroupTransmissionCandidateDecider vs SSM Guard

#### 기존 코드: AdGroupTransmissionCandidateDecider

```java
// src/main/java/.../impl/AdGroupTransmissionCandidateDecider.java (현재 코드)

public final class AdGroupTransmissionCandidateDecider {

    private AdGroupTransmissionCandidateDecider() {}

    /**
     * 종일(Full-time) 스케줄 송출 시작 대상 여부를 판단한다.
     */
    public static boolean isFullTimeStartCandidate(AdGroupsByStatusDto dto) {
        AdGroup adGroup = dto.getAdGroup();
        return adGroup.isReadyForInitialTransmission()
                && isFullTimeStartEligible(dto.getStatus())
                && !adGroup.hasDailySchedule()
                && adGroup.getManualOnAt() == null;
    }

    /**
     * 데일리 스케줄 송출 시작 대상 여부를 판단한다.
     */
    public static boolean isDailyStartCandidate(AdGroupsByStatusDto dto) {
        AdGroup adGroup = dto.getAdGroup();
        return !isPendingManualOff(dto)
                && adGroup.isAtDailyStartTime(dto.getNow())
                && adGroup.getManualOnAt() == null
                && isDailyStartEligible(dto.getStatus());
    }

    /**
     * 수동 ON 후 준비확정 상태에서 송출 전환 대상 여부를 판단한다.
     */
    public static boolean isManualOnCandidate(AdGroupsByStatusDto dto) {
        AdGroup adGroup = dto.getAdGroup();
        return adGroup.isManualOnWaiting()
                && isManualStartEligible(dto.getStatus())
                && !dto.getNow().isBefore(adGroup.getManualOnAt().plusMinutes(10));
    }
}
```

#### SSM 방식: Guard로 분리

```java
// SSM 방식: 각 송출 조건을 Guard로 분리

/**
 * 종일 송출 시작 Guard (기존 isFullTimeStartCandidate 로직)
 */
@Component
public class FullTimeTransmissionGuard implements Guard<AdGroupStatus, AdGroupEvent> {

    @Override
    public boolean evaluate(StateContext<AdGroupStatus, AdGroupEvent> context) {
        AdGroup adGroup = getAdGroup(context);

        // 1. 최초 송출 대상 여부
        if (!adGroup.isReadyForInitialTransmission()) {
            return false;
        }

        // 2. 데일리 스케줄 없음 (종일 송출)
        if (adGroup.hasDailySchedule()) {
            return false;
        }

        // 3. 수동 ON 대기 상태 제외
        if (adGroup.getManualOnAt() != null) {
            return false;
        }

        return true;
    }
}

/**
 * 수동 ON Guard (기존 isManualOnCandidate 로직)
 */
@Component
public class ManualOnGuard implements Guard<AdGroupStatus, AdGroupEvent> {

    @Override
    public boolean evaluate(StateContext<AdGroupStatus, AdGroupEvent> context) {
        AdGroup adGroup = getAdGroup(context);
        LocalDateTime now = LocalDateTime.now();

        // 종료 시각 체크
        if (adGroup.getEndAt().isBefore(now)) {
            return false;
        }

        // 이미 송출 중인 경우 제외
        if (adGroup.isTransmission()) {
            return false;
        }

        return true;
    }
}
```

### 3.3 비교 요약

| 구분 | 기존 방식 | SSM 방식 |
|------|----------|----------|
| **상태 계산** | `AdGroupStatusCalculator.getStatus()` | 명시적 Transition 정의 |
| **필수값 검증** | `isReady()` 내 5단계 검증 | `RequirementFulfilledGuard` |
| **송출 대상 판정** | `TransmissionCandidateDecider` static 메서드들 | 개별 Guard 클래스 |
| **조건 분기** | 7단계 if-else 체인 | 선언적 Transition + Guard |
| **부수 효과** | Service 내 직접 호출 | Action으로 분리 |
| **이력 추적** | Envers만 의존 | Listener로 상세 기록 |
| **테스트** | DTO 기반 단위 테스트 | 상태 전이 기반 테스트 |

---

## 4. 마이그레이션 전략

### 4.1 단계별 마이그레이션 계획

```
┌─────────────────────────────────────────────────────────────────┐
│                    마이그레이션 단계                              │
└─────────────────────────────────────────────────────────────────┘

Phase 1: 준비                          Phase 2: 병행 운영
┌─────────────────────┐                ┌─────────────────────┐
│ • SSM 의존성 추가    │                │ • SSM 결과 로깅      │
│ • Config 작성       │                │ • 기존 로직 유지      │
│ • Guard/Action 작성  │       →       │ • 불일치 모니터링    │
│ • 단위 테스트        │                │ • 점진적 전환        │
└─────────────────────┘                └─────────────────────┘
                                                │
                                                ↓
Phase 4: 정리                          Phase 3: 전환
┌─────────────────────┐                ┌─────────────────────┐
│ • 기존 코드 제거     │                │ • SSM 우선 적용      │
│ • Calculator 삭제   │       ←       │ • 기존 로직 폴백      │
│ • Decider 삭제      │                │ • 통합 테스트        │
│ • 문서 업데이트      │                │ • 성능 검증          │
└─────────────────────┘                └─────────────────────┘
```

### 4.2 Phase 1: 준비

```gradle
// build.gradle - 의존성 추가
dependencies {
    implementation 'org.springframework.statemachine:spring-statemachine-core:4.0.0'
    implementation 'org.springframework.statemachine:spring-statemachine-data-redis:4.0.0'
}
```

```java
// 패키지 구조 생성
domain/ad/adgroup/
├── statemachine/
│   ├── AdGroupStateMachineConfig.java
│   ├── AdGroupEvent.java
│   ├── guards/
│   │   ├── RequirementFulfilledGuard.java
│   │   ├── TransmissionCapableGuard.java
│   │   └── ...
│   ├── actions/
│   │   ├── TransmitOnAction.java
│   │   ├── RedisUpdateAction.java
│   │   └── ...
│   └── listeners/
│       └── AdGroupStateMachineListener.java
```

### 4.3 Phase 2: 병행 운영

```java
/**
 * 병행 운영을 위한 비교 Service
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdGroupStatusComparisonService {

    private final StateMachineFactory<AdGroupStatus, AdGroupEvent> stateMachineFactory;

    /**
     * 기존 로직과 SSM 결과 비교 (검증용)
     */
    public void compareAndLog(String adGroupId, AdGroupValidationDto dto) {
        // 1. 기존 방식 결과
        AdGroupStatus legacyStatus = AdGroupStatusCalculator.getStatus(dto);

        // 2. SSM 방식 결과
        StateMachine<AdGroupStatus, AdGroupEvent> sm =
            stateMachineFactory.getStateMachine(adGroupId);
        AdGroupStatus ssmStatus = sm.getState().getId();

        // 3. 비교 로깅
        if (legacyStatus != ssmStatus) {
            log.warn("Status mismatch detected! adGroupId={}, legacy={}, ssm={}",
                adGroupId, legacyStatus, ssmStatus);

            // 메트릭 기록 (모니터링용)
            meterRegistry.counter("adgroup.status.mismatch",
                "legacy", legacyStatus.name(),
                "ssm", ssmStatus.name()
            ).increment();
        }

        // 4. 기존 로직 결과 반환 (Phase 2에서는 기존 로직 우선)
    }
}
```

### 4.4 Phase 3: 전환

```java
/**
 * SSM 우선 적용 + 기존 로직 폴백
 */
@Service
@RequiredArgsConstructor
public class AdGroupServiceImpl implements AdGroupService {

    @Value("${ad-admin.statemachine.enabled:false}")
    private boolean ssmEnabled;

    @Override
    @AdTransactional
    public void manualOn(String adGroupId) {
        if (ssmEnabled) {
            try {
                manualOnWithSSM(adGroupId);
                return;
            } catch (Exception e) {
                log.error("SSM processing failed, falling back to legacy", e);
                // 폴백 메트릭
                meterRegistry.counter("adgroup.ssm.fallback", "operation", "manualOn").increment();
            }
        }

        // 폴백: 기존 로직
        manualOnLegacy(adGroupId);
    }

    private void manualOnWithSSM(String adGroupId) {
        // SSM 기반 처리
    }

    private void manualOnLegacy(String adGroupId) {
        // 기존 로직
    }
}
```

### 4.5 Phase 4: 정리

```java
// 제거 대상 클래스
// - AdGroupStatusCalculator.java (전체 삭제)
// - AdGroupTransmissionCandidateDecider.java (전체 삭제)

// AdGroupService에서 SSM 전용으로 단순화
@Service
@RequiredArgsConstructor
public class AdGroupServiceImpl implements AdGroupService {

    private final StateMachineFactory<AdGroupStatus, AdGroupEvent> stateMachineFactory;
    private final StateMachinePersister<AdGroupStatus, AdGroupEvent, String> persister;

    @Override
    @AdTransactional
    public void manualOn(String adGroupId) {
        // SSM 전용 로직만 유지
        AdGroup adGroup = findAdGroupById(adGroupId);
        StateMachine<AdGroupStatus, AdGroupEvent> sm = acquireStateMachine(adGroupId, adGroup);

        boolean accepted = sendEvent(sm, AdGroupEvent.MANUAL_ON);
        if (!accepted) {
            throw new InvalidStateTransitionException(...);
        }

        persistAndSave(sm, adGroup);
    }
}
```

---

## 5. 롤백 전략

### 5.1 롤백 시나리오

| 시나리오 | 롤백 수준 | 조치 |
|----------|----------|------|
| SSM 설정 오류 | 기능 플래그 OFF | `ad-admin.statemachine.enabled=false` |
| Guard 로직 버그 | 코드 롤백 | 이전 버전 배포 |
| 성능 이슈 | 기능 플래그 OFF | SSM 비활성화 |
| 데이터 불일치 | 수동 복구 | 상태 재계산 배치 |

### 5.2 기능 플래그 기반 롤백

```java
@Configuration
@ConditionalOnProperty(name = "ad-admin.statemachine.enabled", havingValue = "true")
public class AdGroupStateMachineConfig {
    // SSM 설정
}

@Configuration
@ConditionalOnProperty(name = "ad-admin.statemachine.enabled", havingValue = "false", matchIfMissing = true)
public class AdGroupLegacyConfig {
    // 기존 로직 Bean 등록
}
```

### 5.3 상태 복구 배치

```java
/**
 * 상태 불일치 발생 시 복구 배치
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdGroupStatusRecoveryBatch {

    private final AdGroupRepository adGroupRepository;
    private final StateMachinePersister<AdGroupStatus, AdGroupEvent, String> persister;

    /**
     * 모든 광고그룹의 상태를 재계산하여 SSM과 동기화
     */
    @Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
    public void recoverStatus() {
        log.info("Starting status recovery batch");

        List<AdGroup> adGroups = adGroupRepository.findAll();
        int recovered = 0;

        for (AdGroup adGroup : adGroups) {
            try {
                // 기존 Calculator로 상태 계산
                AdGroupStatus calculatedStatus = AdGroupStatusCalculator.getStatus(
                    toValidationDto(adGroup)
                );

                // SSM 상태와 비교
                StateMachine<AdGroupStatus, AdGroupEvent> sm =
                    stateMachineFactory.getStateMachine(adGroup.getId());
                persister.restore(sm, adGroup.getId());

                if (sm.getState().getId() != calculatedStatus) {
                    // 상태 동기화
                    resetStateMachine(sm, calculatedStatus);
                    persister.persist(sm, adGroup.getId());
                    recovered++;

                    log.info("Status recovered: adGroupId={}, from={}, to={}",
                        adGroup.getId(), sm.getState().getId(), calculatedStatus);
                }
            } catch (Exception e) {
                log.error("Failed to recover status: adGroupId={}", adGroup.getId(), e);
            }
        }

        log.info("Status recovery completed: total={}, recovered={}", adGroups.size(), recovered);
    }
}
```

---

## 6. 모니터링 연동

### 6.1 Prometheus 메트릭

```java
/**
 * SSM 메트릭 수집 Listener
 */
@Component
@RequiredArgsConstructor
public class AdGroupStateMachineMetricsListener
        extends StateMachineListenerAdapter<AdGroupStatus, AdGroupEvent> {

    private final MeterRegistry meterRegistry;

    private final Timer transitionTimer;
    private final Counter transitionCounter;
    private final Counter errorCounter;

    public AdGroupStateMachineMetricsListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.transitionTimer = Timer.builder("adgroup.statemachine.transition.duration")
            .description("State transition duration")
            .register(meterRegistry);

        this.transitionCounter = Counter.builder("adgroup.statemachine.transition.total")
            .description("Total state transitions")
            .register(meterRegistry);

        this.errorCounter = Counter.builder("adgroup.statemachine.error.total")
            .description("Total state machine errors")
            .register(meterRegistry);
    }

    @Override
    public void stateChanged(State<AdGroupStatus, AdGroupEvent> from,
                            State<AdGroupStatus, AdGroupEvent> to) {
        // 상태 전이 카운터
        transitionCounter.increment();

        // 상태별 카운터
        meterRegistry.counter("adgroup.statemachine.state",
            "from", from != null ? from.getId().name() : "NONE",
            "to", to.getId().name()
        ).increment();
    }

    @Override
    public void transitionStarted(Transition<AdGroupStatus, AdGroupEvent> transition) {
        // 타이머 시작 (ThreadLocal로 관리)
        TransitionTimerHolder.start();
    }

    @Override
    public void transitionEnded(Transition<AdGroupStatus, AdGroupEvent> transition) {
        // 타이머 종료
        long duration = TransitionTimerHolder.stop();
        transitionTimer.record(duration, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stateMachineError(StateMachine<AdGroupStatus, AdGroupEvent> stateMachine,
                                  Exception exception) {
        errorCounter.increment();

        meterRegistry.counter("adgroup.statemachine.error",
            "type", exception.getClass().getSimpleName()
        ).increment();
    }

    @Override
    public void eventNotAccepted(Message<AdGroupEvent> event) {
        meterRegistry.counter("adgroup.statemachine.event.rejected",
            "event", event.getPayload().name()
        ).increment();
    }
}
```

### 6.2 Grafana 대시보드 쿼리

```promql
# 상태 전이 속도 (초당)
rate(adgroup_statemachine_transition_total[5m])

# 평균 전이 시간
rate(adgroup_statemachine_transition_duration_seconds_sum[5m])
/ rate(adgroup_statemachine_transition_duration_seconds_count[5m])

# 에러율
rate(adgroup_statemachine_error_total[5m])
/ rate(adgroup_statemachine_transition_total[5m]) * 100

# 상태별 분포
sum by (to) (increase(adgroup_statemachine_state_total[1h]))

# 거부된 이벤트
sum by (event) (increase(adgroup_statemachine_event_rejected_total[1h]))
```

### 6.3 알림 설정

```yaml
# alertmanager rules
groups:
  - name: statemachine
    rules:
      - alert: HighStateMachineErrorRate
        expr: |
          rate(adgroup_statemachine_error_total[5m])
          / rate(adgroup_statemachine_transition_total[5m]) > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "State machine error rate is high"
          description: "Error rate is {{ $value | humanizePercentage }}"

      - alert: SlowStateTransition
        expr: |
          histogram_quantile(0.99,
            rate(adgroup_statemachine_transition_duration_seconds_bucket[5m])
          ) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "State transitions are slow"
          description: "P99 latency is {{ $value | humanizeDuration }}"
```

---

## 7. FAQ

### Q1. SSM 도입 시 기존 데이터는 어떻게 처리하나요?

**A.** 기존 광고그룹의 상태는 Entity에 저장되어 있으므로, SSM 도입 시 다음과 같이 초기화합니다:

```java
// 첫 조회 시 Entity 상태를 SSM에 동기화
private StateMachine<AdGroupStatus, AdGroupEvent> acquireStateMachine(
        String adGroupId, AdGroup adGroup) {

    StateMachine<AdGroupStatus, AdGroupEvent> sm =
        stateMachineFactory.getStateMachine(adGroupId);

    try {
        // Redis에서 저장된 상태 복원 시도
        persister.restore(sm, adGroupId);
    } catch (Exception e) {
        // 저장된 상태 없으면 Entity 상태로 초기화
        log.debug("No persisted state, initializing from entity: adGroupId={}", adGroupId);
        resetStateMachine(sm, adGroup.getStatus());
    }

    return sm;
}

private void resetStateMachine(StateMachine<AdGroupStatus, AdGroupEvent> sm,
                               AdGroupStatus status) {
    sm.getStateMachineAccessor()
        .doWithAllRegions(accessor -> accessor
            .resetStateMachine(new DefaultStateMachineContext<>(status, null, null, null)));
}
```

### Q2. 배치 작업에서 SSM을 어떻게 사용하나요?

**A.** 배치 작업도 동일하게 이벤트를 발송합니다:

```java
@Scheduled(cron = "0 0 * * * *")  // 매시 정각
public void processScheduledTransmissions() {
    // READY_CONFIRM 상태 + 시작 시각 도래한 광고그룹 조회
    List<AdGroup> candidates = adGroupRepository.findByStatusAndStartAtBefore(
        AdGroupStatus.READY_CONFIRM,
        LocalDateTime.now()
    );

    for (AdGroup adGroup : candidates) {
        try {
            // SSM 이벤트 발송
            adGroupService.processTransmissionStart(adGroup.getId());
        } catch (Exception e) {
            log.error("Batch processing failed: adGroupId={}", adGroup.getId(), e);
        }
    }
}
```

### Q3. Guard에서 외부 API 호출 시 타임아웃은 어떻게 처리하나요?

**A.** Guard에서 외부 API 호출은 신중하게 설계해야 합니다:

```java
@Component
@RequiredArgsConstructor
public class SlotMachineAvailableGuard implements Guard<AdGroupStatus, AdGroupEvent> {

    private final EventApiService eventApiService;

    @Override
    public boolean evaluate(StateContext<AdGroupStatus, AdGroupEvent> context) {
        AdGroup adGroup = getAdGroup(context);

        if (!adGroup.getCreative().getType().isSlotMachine()) {
            return true;  // 슬롯머신이 아니면 통과
        }

        try {
            // 타임아웃 설정된 API 호출
            return eventApiService.checkSlotMachineAvailabilityWithTimeout(
                adGroup.getCreative().getId(),
                Duration.ofSeconds(3)  // 3초 타임아웃
            );
        } catch (TimeoutException e) {
            log.warn("Slot machine check timeout: adGroupId={}", adGroup.getId());
            return false;  // 타임아웃 시 전이 거부
        } catch (Exception e) {
            log.error("Slot machine check failed: adGroupId={}", adGroup.getId(), e);
            return false;  // 에러 시 전이 거부
        }
    }
}
```

### Q4. 상태 전이 중 예외 발생 시 롤백되나요?

**A.** SSM 자체는 트랜잭션을 관리하지 않습니다. 트랜잭션 관리는 Service 레이어에서 처리합니다:

```java
@Service
public class AdGroupServiceImpl {

    @AdTransactional  // 트랜잭션 시작
    public void manualOn(String adGroupId) {
        // 1. Entity 조회
        AdGroup adGroup = findAdGroupById(adGroupId);

        // 2. SSM 이벤트 처리 (Action에서 Entity 변경)
        StateMachine<AdGroupStatus, AdGroupEvent> sm = acquireStateMachine(adGroupId, adGroup);
        sendEvent(sm, AdGroupEvent.MANUAL_ON);

        // 3. 저장 (Action에서 변경된 Entity가 트랜잭션에 포함됨)
        persistAndSave(sm, adGroup);

        // 트랜잭션 커밋
    }
    // 예외 발생 시 전체 롤백 (@AdTransactional)
}
```

### Q5. SSM과 기존 로직을 동시에 사용할 수 있나요?

**A.** 네, 병행 운영이 가능합니다. 마이그레이션 Phase 2, 3 참조.

---

## 8. 트러블슈팅

### 8.1 "Event not accepted" 오류

**증상:** 이벤트를 보냈는데 `eventNotAccepted` 콜백이 호출됨

**원인:**
1. 현재 상태에서 해당 이벤트를 처리할 Transition이 없음
2. Guard 조건이 false를 반환

**해결:**

```java
// 디버깅: 현재 상태에서 가능한 이벤트 확인
public List<AdGroupEvent> getAvailableEvents(String adGroupId) {
    StateMachine<AdGroupStatus, AdGroupEvent> sm = acquireStateMachine(adGroupId);

    AdGroupStatus currentState = sm.getState().getId();

    return sm.getTransitions().stream()
        .filter(t -> t.getSource().getId() == currentState)
        .map(t -> t.getTrigger().getEvent())
        .distinct()
        .toList();
}

// 사용
log.info("Current state: {}, Available events: {}",
    sm.getState().getId(),
    getAvailableEvents(adGroupId));
```

### 8.2 상태 불일치 (Entity vs SSM)

**증상:** Entity의 상태와 SSM의 상태가 다름

**원인:**
1. SSM 영속화 실패
2. Entity 직접 수정 (SSM 우회)
3. 복구 배치 미실행

**해결:**

```java
// 상태 강제 동기화
public void forceSync(String adGroupId) {
    AdGroup adGroup = findAdGroupById(adGroupId);
    StateMachine<AdGroupStatus, AdGroupEvent> sm = acquireStateMachine(adGroupId, adGroup);

    // Entity 상태로 SSM 초기화
    sm.getStateMachineAccessor()
        .doWithAllRegions(accessor -> accessor
            .resetStateMachine(new DefaultStateMachineContext<>(
                adGroup.getStatus(), null, null, null)));

    // 영속화
    persister.persist(sm, adGroupId);

    log.info("Force synced: adGroupId={}, status={}", adGroupId, adGroup.getStatus());
}
```

### 8.3 분산 락 획득 실패

**증상:** `ConcurrentModificationException` 발생

**원인:**
1. 다른 요청이 락을 오래 잡고 있음
2. 락 타임아웃이 너무 짧음
3. Redis 연결 문제

**해결:**

```java
// 락 타임아웃 조정
public LockContext tryLock(String adGroupId) {
    return tryLock(
        adGroupId,
        Duration.ofSeconds(30),  // 락 유지 시간 증가
        Duration.ofSeconds(10)   // 대기 시간 증가
    );
}

// 락 상태 확인
public boolean isLocked(String adGroupId) {
    String lockKey = LOCK_PREFIX + adGroupId;
    return redisTemplate.hasKey(lockKey);
}

// 강제 락 해제 (관리자용)
public void forceUnlock(String adGroupId) {
    String lockKey = LOCK_PREFIX + adGroupId;
    redisTemplate.delete(lockKey);
    log.warn("Force unlocked: adGroupId={}", adGroupId);
}
```

### 8.4 Action 실행 순서 문제

**증상:** Action들이 예상과 다른 순서로 실행됨

**원인:** Transition에 여러 Action을 등록할 때 순서 보장이 안 됨

**해결:**

```java
// 방법 1: 하나의 Action에서 순차 처리
@Component
public class TransmissionStartCompositeAction implements Action<AdGroupStatus, AdGroupEvent> {

    @Override
    public void execute(StateContext<AdGroupStatus, AdGroupEvent> context) {
        // 순서대로 실행
        transmitOn(context);      // 1. Entity 상태 변경
        updateRedis(context);     // 2. Redis 갱신
        sendSlackNotify(context); // 3. Slack 알림
    }
}

// 방법 2: Actions 컬렉션으로 순서 보장
transitions.withExternal()
    .source(READY_CONFIRM)
    .target(RUNNING)
    .event(TRANSMISSION_START)
    .action(ctx -> {
        transmitOnAction.execute(ctx);
        redisUpdateAction.execute(ctx);
        slackNotifyAction.execute(ctx);
    });
```

### 8.5 성능 문제

**증상:** 상태 전이가 느림

**원인:**
1. Guard에서 외부 API 호출
2. Action에서 동기 처리
3. 영속화 오버헤드

**해결:**

```java
// 1. Guard에서 캐싱 적용
@Component
public class SlotMachineAvailableGuard implements Guard<AdGroupStatus, AdGroupEvent> {

    @Cacheable(value = "slotMachineAvailability", key = "#creativeId")
    public boolean checkAvailability(String creativeId) {
        return eventApiService.checkSlotMachineAvailability(creativeId);
    }
}

// 2. Action에서 비동기 처리 (결과가 중요하지 않은 경우)
@Component
public class SlackNotifyAction implements Action<AdGroupStatus, AdGroupEvent> {

    @Async
    public void execute(StateContext<AdGroupStatus, AdGroupEvent> context) {
        // 비동기로 Slack 알림 발송
    }
}

// 3. 배치 처리 시 벌크 연산
public void processBulkTransmissions(List<String> adGroupIds) {
    // 한 번에 조회
    List<AdGroup> adGroups = adGroupRepository.findAllById(adGroupIds);

    // 병렬 처리
    adGroups.parallelStream().forEach(adGroup -> {
        processTransmission(adGroup);
    });
}
```

---

## 참고 자료

- [Spring State Machine 기술 가이드](./spring-state-machine-guide.md) - 기본 개념
- [Spring State Machine 프로젝트 도입 검토](./spring-state-machine-adoption.md) - 도입 분석
- [Spring State Machine 공식 문서](https://docs.spring.io/spring-statemachine/docs/current/reference/)
- [Spring State Machine GitHub](https://github.com/spring-projects/spring-statemachine)
