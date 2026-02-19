#!/bin/bash
# Spring State Machine 성공 시나리오 테스트 스크립트
# 정상적인 상태 전이를 테스트합니다.

BASE_URL="http://localhost:8080/api/orders"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo ""
echo "=========================================="
echo "  Spring State Machine 성공 시나리오 테스트"
echo "=========================================="
echo ""

# 테스트 결과 카운터
PASSED=0
FAILED=0

# 테스트 함수
assert_status() {
    local expected_status=$1
    local result=$2
    local test_name=$3

    actual_status=$(echo $result | jq -r '.status // empty')

    if [ "$actual_status" == "$expected_status" ]; then
        echo -e "    ${GREEN}✓ PASS${NC}: $test_name"
        echo "    → 상태: $actual_status ($(echo $result | jq -r '.statusDescription'))"
        ((PASSED++))
    else
        echo -e "    ${RED}✗ FAIL${NC}: $test_name"
        echo "    → 예상: $expected_status, 실제: $actual_status"
        echo "    → 응답: $result"
        ((FAILED++))
    fi
}

echo "===== 시나리오 1: 정상 주문 흐름 ====="
echo "CREATED → PAID → SHIPPED → DELIVERED"
echo ""

RESULT=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-001","quantity":1,"amount":30000,"customerEmail":"test@test.com","paymentMethod":"CARD"}')
ORDER_ID=$(echo $RESULT | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID"
assert_status "CREATED" "$RESULT" "주문 생성 시 CREATED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/pay)
assert_status "PAID" "$RESULT" "결제 처리 시 PAID 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/ship)
assert_status "SHIPPED" "$RESULT" "배송 시작 시 SHIPPED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/deliver)
assert_status "DELIVERED" "$RESULT" "배송 완료 시 DELIVERED 상태"

echo ""
echo "===== 시나리오 2: 주문 생성 후 바로 취소 ====="
echo "CREATED → CANCELLED"
echo ""

RESULT=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-002","quantity":1,"amount":20000,"customerEmail":"test@test.com","paymentMethod":"CARD"}')
ORDER_ID=$(echo $RESULT | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID"
assert_status "CREATED" "$RESULT" "주문 생성 시 CREATED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/cancel)
assert_status "CANCELLED" "$RESULT" "주문 취소 시 CANCELLED 상태"

echo ""
echo "===== 시나리오 3: 결제 후 취소 (환불) ====="
echo "CREATED → PAID → CANCELLED"
echo ""

RESULT=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-003","quantity":1,"amount":40000,"customerEmail":"test@test.com","paymentMethod":"CARD"}')
ORDER_ID=$(echo $RESULT | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID"
assert_status "CREATED" "$RESULT" "주문 생성 시 CREATED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/pay)
assert_status "PAID" "$RESULT" "결제 처리 시 PAID 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/cancel)
assert_status "CANCELLED" "$RESULT" "결제 후 취소 시 CANCELLED 상태 (환불)"

echo ""
echo "===== 시나리오 4: 반품 처리 ====="
echo "CREATED → PAID → SHIPPED → DELIVERED → RETURNED"
echo ""

RESULT=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-004","quantity":1,"amount":35000,"customerEmail":"test@test.com","paymentMethod":"CARD"}')
ORDER_ID=$(echo $RESULT | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID"
assert_status "CREATED" "$RESULT" "주문 생성 시 CREATED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/pay)
assert_status "PAID" "$RESULT" "결제 처리 시 PAID 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/ship)
assert_status "SHIPPED" "$RESULT" "배송 시작 시 SHIPPED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/deliver)
assert_status "DELIVERED" "$RESULT" "배송 완료 시 DELIVERED 상태"

sleep 0.5

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/return)
assert_status "RETURNED" "$RESULT" "반품 처리 시 RETURNED 상태"

echo ""
echo "===== 시나리오 5: 가능한 이벤트 조회 ====="
echo "각 상태에서 가능한 이벤트 확인"
echo ""

RESULT=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-005","quantity":1,"amount":25000,"customerEmail":"test@test.com","paymentMethod":"CARD"}')
ORDER_ID=$(echo $RESULT | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

EVENTS=$(curl -s $BASE_URL/$ORDER_ID/available-events)
AVAILABLE=$(echo $EVENTS | jq -r '.availableEvents | map(.event) | join(", ")')
echo -e "    ${GREEN}✓ INFO${NC}: CREATED 상태에서 가능한 이벤트"
echo "    → 가능한 이벤트: $AVAILABLE"
((PASSED++))

sleep 0.5

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
echo "[준비] 결제 완료 (상태: PAID)"

EVENTS=$(curl -s $BASE_URL/$ORDER_ID/available-events)
AVAILABLE=$(echo $EVENTS | jq -r '.availableEvents | map(.event) | join(", ")')
echo -e "    ${GREEN}✓ INFO${NC}: PAID 상태에서 가능한 이벤트"
echo "    → 가능한 이벤트: $AVAILABLE"
((PASSED++))

sleep 0.5

curl -s -X POST $BASE_URL/$ORDER_ID/ship > /dev/null
echo "[준비] 배송 시작 (상태: SHIPPED)"

EVENTS=$(curl -s $BASE_URL/$ORDER_ID/available-events)
AVAILABLE=$(echo $EVENTS | jq -r '.availableEvents | map(.event) | join(", ")')
echo -e "    ${GREEN}✓ INFO${NC}: SHIPPED 상태에서 가능한 이벤트"
echo "    → 가능한 이벤트: $AVAILABLE"
((PASSED++))

echo ""
echo "=========================================="
echo "  테스트 결과 요약"
echo "=========================================="
echo ""
echo -e "  ${GREEN}통과: $PASSED${NC}"
echo -e "  ${RED}실패: $FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "  ${GREEN}✓ 모든 성공 시나리오 테스트 통과!${NC}"
else
    echo -e "  ${RED}✗ 일부 테스트 실패${NC}"
fi

echo ""
echo "=========================================="
echo "  전체 주문 상태 요약"
echo "=========================================="
echo ""
curl -s $BASE_URL | jq -r '.[] | "  주문 \(.id): \(.status) (\(.statusDescription))"'

echo ""
echo "=========================================="
echo "  테스트 완료"
echo "=========================================="
echo ""
