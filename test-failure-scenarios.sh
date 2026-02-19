#!/bin/bash
# Spring State Machine 실패 시나리오 테스트 스크립트
# 서버에서 예외가 발생하는 케이스를 테스트합니다.

BASE_URL="http://localhost:8080/api/orders"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo ""
echo "=========================================="
echo "  Spring State Machine 실패 시나리오 테스트"
echo "=========================================="
echo ""

# 테스트 결과 카운터
PASSED=0
FAILED=0

# 테스트 함수
assert_error() {
    local expected_error=$1
    local result=$2
    local test_name=$3

    actual_error=$(echo $result | jq -r '.error // empty')

    if [ "$actual_error" == "$expected_error" ]; then
        echo -e "    ${GREEN}✓ PASS${NC}: $test_name"
        echo "    → 에러 코드: $actual_error"
        echo "    → 메시지: $(echo $result | jq -r '.message')"
        ((PASSED++))
    else
        echo -e "    ${RED}✗ FAIL${NC}: $test_name"
        echo "    → 예상: $expected_error, 실제: $actual_error"
        echo "    → 응답: $result"
        ((FAILED++))
    fi
    echo ""
}

echo "===== 테스트 1: 존재하지 않는 주문 조회 ====="
echo "GET /api/orders/NON_EXISTENT_ORDER"
echo ""

RESULT=$(curl -s -X GET $BASE_URL/NON_EXISTENT_ORDER)
assert_error "NOT_FOUND" "$RESULT" "존재하지 않는 주문 조회 시 404 반환"

sleep 0.5

echo "===== 테스트 2: CREATED에서 바로 SHIP 시도 ====="
echo "결제 없이 배송 시작 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-001","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/ship)
assert_error "INVALID_TRANSITION" "$RESULT" "CREATED → SHIP 전이 거부"

sleep 0.5

echo "===== 테스트 3: CREATED에서 바로 DELIVER 시도 ====="
echo "결제/배송 없이 배송완료 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-002","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/deliver)
assert_error "INVALID_TRANSITION" "$RESULT" "CREATED → DELIVER 전이 거부"

sleep 0.5

echo "===== 테스트 4: CREATED에서 바로 RETURN 시도 ====="
echo "배송완료 전 반품 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-003","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/return)
assert_error "INVALID_TRANSITION" "$RESULT" "CREATED → RETURN 전이 거부"

sleep 0.5

echo "===== 테스트 5: PAID에서 바로 DELIVER 시도 ====="
echo "배송 시작 없이 배송완료 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-004","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
echo "[준비] 결제 완료 (상태: PAID)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/deliver)
assert_error "INVALID_TRANSITION" "$RESULT" "PAID → DELIVER 전이 거부"

sleep 0.5

echo "===== 테스트 6: SHIPPED에서 CANCEL 시도 ====="
echo "배송 중 취소 불가 (Guard 검증)"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-005","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
echo "[준비] 결제 완료 (상태: PAID)"

curl -s -X POST $BASE_URL/$ORDER_ID/ship > /dev/null
echo "[준비] 배송 시작 (상태: SHIPPED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/cancel)
assert_error "INVALID_TRANSITION" "$RESULT" "SHIPPED → CANCEL 전이 거부"

sleep 0.5

echo "===== 테스트 7: CANCELLED 상태에서 추가 전이 시도 ====="
echo "취소된 주문은 더 이상 전이 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-006","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

curl -s -X POST $BASE_URL/$ORDER_ID/cancel > /dev/null
echo "[준비] 주문 취소 (상태: CANCELLED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/pay)
assert_error "INVALID_TRANSITION" "$RESULT" "CANCELLED → PAY 전이 거부"

sleep 0.5

echo "===== 테스트 8: DELIVERED에서 SHIP 시도 ====="
echo "배송완료 후 다시 배송 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-007","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/ship > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/deliver > /dev/null
echo "[준비] 배송 완료 (상태: DELIVERED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/ship)
assert_error "INVALID_TRANSITION" "$RESULT" "DELIVERED → SHIP 전이 거부"

sleep 0.5

echo "===== 테스트 9: RETURNED에서 추가 전이 시도 ====="
echo "반품 완료 후 추가 전이 불가"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"TEST-008","quantity":1,"amount":10000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[준비] 주문 생성: $ORDER_ID (상태: CREATED)"

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/ship > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/deliver > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/return > /dev/null
echo "[준비] 반품 완료 (상태: RETURNED)"

RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/cancel)
assert_error "INVALID_TRANSITION" "$RESULT" "RETURNED → CANCEL 전이 거부"

echo ""
echo "=========================================="
echo "  테스트 결과 요약"
echo "=========================================="
echo ""
echo -e "  ${GREEN}통과: $PASSED${NC}"
echo -e "  ${RED}실패: $FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "  ${GREEN}✓ 모든 실패 시나리오 테스트 통과!${NC}"
else
    echo -e "  ${RED}✗ 일부 테스트 실패${NC}"
fi

echo ""
echo "=========================================="
echo "  서버 로그에서 예외 처리 확인 가능"
echo "=========================================="
echo ""
