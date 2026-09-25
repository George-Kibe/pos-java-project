#!/usr/bin/env bash
# Creates every topic in the event catalogue (docs/ARCHITECTURE.md) plus its dead-letter
# topic. Idempotent: existing topics are left untouched. Auto-creation is disabled on the
# broker on purpose, so a typo in a topic name fails loudly instead of silently creating one.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
KT="/opt/kafka/bin/kafka-topics.sh"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
REPLICATION="${TOPIC_REPLICATION:-1}"

DAY_MS=86400000
RETENTION_DEFAULT=$((7 * DAY_MS))    # transactional events
RETENTION_SALES=$((30 * DAY_MS))     # reporting rebuild window
RETENTION_SECRET=$((1 * DAY_MS))     # payloads carrying a live credential (OTP, reset token)

# topic|retention_ms|cleanup_policy
TOPICS="
pos.auth.otp-requested.v1|${RETENTION_SECRET}|delete
pos.auth.user-registered.v1|${RETENTION_DEFAULT}|delete
pos.auth.password-reset-requested.v1|${RETENTION_SECRET}|delete
pos.auth.user-role-changed.v1|${RETENTION_DEFAULT}|delete
pos.catalog.product-changed.v1|-1|compact
pos.catalog.price-changed.v1|${RETENTION_SALES}|delete
pos.purchasing.po-approved.v1|${RETENTION_DEFAULT}|delete
pos.purchasing.goods-received.v1|${RETENTION_SALES}|delete
pos.purchasing.supplier-return-sent.v1|${RETENTION_SALES}|delete
pos.purchasing.supplier-cost-changed.v1|${RETENTION_DEFAULT}|delete
pos.purchasing.expense-changed.v1|-1|compact
pos.payments.payment-requested.v1|${RETENTION_DEFAULT}|delete
pos.payments.payment-authorized.v1|${RETENTION_SALES}|delete
pos.payments.payment-failed.v1|${RETENTION_DEFAULT}|delete
pos.payments.payment-refunded.v1|${RETENTION_SALES}|delete
pos.sales.sale-completed.v1|${RETENTION_SALES}|delete
pos.sales.sale-voided.v1|${RETENTION_SALES}|delete
pos.sales.sale-cancelled.v1|${RETENTION_DEFAULT}|delete
pos.sales.return-processed.v1|${RETENTION_SALES}|delete
pos.sales.shift-closed.v1|${RETENTION_SALES}|delete
pos.sales.receipt-email-requested.v1|${RETENTION_DEFAULT}|delete
pos.inventory.stock-deducted.v1|${RETENTION_SALES}|delete
pos.inventory.low-stock.v1|${RETENTION_DEFAULT}|delete
pos.inventory.stock-valued.v1|${RETENTION_SALES}|delete
pos.inventory.batch-expiring.v1|${RETENTION_DEFAULT}|delete
pos.inventory.negative-stock-detected.v1|${RETENTION_DEFAULT}|delete
pos.inventory.adjustment-posted.v1|${RETENTION_SALES}|delete
pos.customers.loyalty-accrued.v1|${RETENTION_SALES}|delete
pos.customers.tier-changed.v1|${RETENTION_DEFAULT}|delete
"

echo "Waiting for broker at ${BOOTSTRAP}..."
for _ in $(seq 1 30); do
    if "$KT" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; then break; fi
    sleep 2
done

create() {
    local name="$1" retention="$2" policy="$3"
    if "$KT" --bootstrap-server "$BOOTSTRAP" --list 2>/dev/null | grep -qx "$name"; then
        echo "  = ${name} (exists)"
        return
    fi
    "$KT" --bootstrap-server "$BOOTSTRAP" --create \
        --topic "$name" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION" \
        --config "retention.ms=${retention}" \
        --config "cleanup.policy=${policy}" >/dev/null
    echo "  + ${name}  (partitions=${PARTITIONS}, retention=${retention}, policy=${policy})"
}

echo "$TOPICS" | while IFS='|' read -r topic retention policy; do
    [ -z "$topic" ] && continue
    create "$topic" "$retention" "$policy"
    # Dead letter: always delete-policy, longer retention, single partition ordering is
    # irrelevant here but keep it matched for straightforward replay.
    create "${topic}.dlt" "$((30 * DAY_MS))" "delete"
done

echo
echo "Topics now present:"
"$KT" --bootstrap-server "$BOOTSTRAP" --list | sort
