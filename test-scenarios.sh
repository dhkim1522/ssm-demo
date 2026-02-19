#!/bin/bash
# Spring State Machine 상태 전이 시연 스크립트

BASE_URL="http://localhost:8080/api/orders"

echo ""
echo "=========================================="
echo "  Spring State Machine 상태 전이 시연"
echo "=========================================="
echo ""

echo "===== 시나리오 1: 정상 주문 흐름 ====="
echo "CREATED → PAID → SHIPPED → DELIVERED"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-001","quantity":1,"amount":30000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[1] 주문 생성 완료: $ORDER_ID (상태: CREATED)"
sleep 1

echo "[2] 결제 처리 중..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/pay)
echo "    → 상태: $(echo $RESULT | jq -r '.status')"
sleep 1

echo "[3] 배송 시작 중..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/ship)
echo "    → 상태: $(echo $RESULT | jq -r '.status')"
sleep 1

echo "[4] 배송 완료 처리 중..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/deliver)
echo "    → 상태: $(echo $RESULT | jq -r '.status')"
sleep 1

echo ""
echo "===== 시나리오 2: 잘못된 전이 시도 ====="
echo "CREATED에서 바로 SHIP 시도 (결제 없이)"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-002","quantity":1,"amount":20000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[1] 주문 생성 완료: $ORDER_ID (상태: CREATED)"
sleep 1

echo "[2] 결제 없이 배송 시도..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/ship)
echo "    → 결과: $(echo $RESULT | jq -r '.error // .status')"
echo "    → 메시지: $(echo $RESULT | jq -r '.message // empty')"
sleep 1

echo ""
echo "===== 시나리오 3: 결제 후 취소 (환불) ====="
echo "CREATED → PAID → CANCELLED"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-003","quantity":1,"amount":40000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[1] 주문 생성 완료: $ORDER_ID (상태: CREATED)"
sleep 1

echo "[2] 결제 처리 중..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/pay)
echo "    → 상태: $(echo $RESULT | jq -r '.status')"
sleep 1

echo "[3] 주문 취소 (환불) 처리 중..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/cancel)
echo "    → 상태: $(echo $RESULT | jq -r '.status')"
sleep 1

echo ""
echo "===== 시나리오 4: 배송 중 취소 불가 ====="
echo "SHIPPED 상태에서 CANCEL 시도"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-004","quantity":1,"amount":25000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[1] 주문 생성 완료: $ORDER_ID (상태: CREATED)"
sleep 1

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
echo "[2] 결제 완료 (상태: PAID)"
sleep 1

curl -s -X POST $BASE_URL/$ORDER_ID/ship > /dev/null
echo "[3] 배송 시작 (상태: SHIPPED)"
sleep 1

echo "[4] 배송 중 취소 시도..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/cancel)
echo "    → 결과: $(echo $RESULT | jq -r '.error // .status')"
echo "    → 메시지: $(echo $RESULT | jq -r '.message // empty')"
sleep 1

echo ""
echo "===== 시나리오 5: 반품 처리 ====="
echo "DELIVERED → RETURNED"
echo ""

ORDER_ID=$(curl -s -X POST $BASE_URL \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-005","quantity":1,"amount":35000,"customerEmail":"test@test.com","paymentMethod":"CARD"}' \
  | jq -r '.id')
echo "[1] 주문 생성 완료: $ORDER_ID"

curl -s -X POST $BASE_URL/$ORDER_ID/pay > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/ship > /dev/null
curl -s -X POST $BASE_URL/$ORDER_ID/deliver > /dev/null
echo "[2] 배송 완료 (상태: DELIVERED)"
sleep 1

echo "[3] 반품 처리 중..."
RESULT=$(curl -s -X POST $BASE_URL/$ORDER_ID/return)
echo "    → 상태: $(echo $RESULT | jq -r '.status')"
sleep 1

echo ""
echo "=========================================="
echo "  전체 주문 상태 요약"
echo "=========================================="
echo ""
curl -s $BASE_URL | jq -r '.[] | "주문 \(.id): \(.status) (\(.statusDescription))"'

echo ""
echo "=========================================="
echo "  시연 완료"
echo "=========================================="
