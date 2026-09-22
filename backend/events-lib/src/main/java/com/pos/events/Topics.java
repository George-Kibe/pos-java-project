package com.pos.events;

/**
 * Every Kafka topic in the system. Topics are created explicitly by {@code
 * infra/kafka/create-topics.sh}; broker auto-creation is disabled, so a name that is not in this
 * class and in that script will fail loudly at runtime rather than silently creating a topic.
 *
 * <p>Naming: {@code pos.<domain>.<event>.v<version>}. A breaking payload change means a new {@code
 * .v2} topic and a period of dual publishing, never a redefinition of {@code .v1}.
 */
public final class Topics {

    private Topics() {}

    /** Suffix applied to every dead-letter topic. */
    public static final String DLT_SUFFIX = ".dlt";

    // --- auth -----------------------------------------------------------------
    public static final String AUTH_OTP_REQUESTED = "pos.auth.otp-requested.v1";
    public static final String AUTH_USER_REGISTERED = "pos.auth.user-registered.v1";
    public static final String AUTH_PASSWORD_RESET_REQUESTED =
            "pos.auth.password-reset-requested.v1";
    public static final String AUTH_USER_ROLE_CHANGED = "pos.auth.user-role-changed.v1";

    // --- catalog --------------------------------------------------------------
    public static final String CATALOG_PRODUCT_CHANGED = "pos.catalog.product-changed.v1";
    public static final String CATALOG_PRICE_CHANGED = "pos.catalog.price-changed.v1";

    // --- purchasing -----------------------------------------------------------
    public static final String PURCHASING_PO_APPROVED = "pos.purchasing.po-approved.v1";
    public static final String PURCHASING_GOODS_RECEIVED = "pos.purchasing.goods-received.v1";
    public static final String PURCHASING_SUPPLIER_COST_CHANGED =
            "pos.purchasing.supplier-cost-changed.v1";

    // --- payments -------------------------------------------------------------
    public static final String PAYMENTS_PAYMENT_REQUESTED = "pos.payments.payment-requested.v1";
    public static final String PAYMENTS_PAYMENT_AUTHORIZED = "pos.payments.payment-authorized.v1";
    public static final String PAYMENTS_PAYMENT_FAILED = "pos.payments.payment-failed.v1";
    public static final String PAYMENTS_PAYMENT_REFUNDED = "pos.payments.payment-refunded.v1";

    // --- sales ----------------------------------------------------------------
    public static final String SALES_SALE_COMPLETED = "pos.sales.sale-completed.v1";
    public static final String SALES_SALE_VOIDED = "pos.sales.sale-voided.v1";
    public static final String SALES_SALE_CANCELLED = "pos.sales.sale-cancelled.v1";
    public static final String SALES_RETURN_PROCESSED = "pos.sales.return-processed.v1";
    public static final String SALES_SHIFT_CLOSED = "pos.sales.shift-closed.v1";

    // --- inventory ------------------------------------------------------------
    public static final String INVENTORY_STOCK_DEDUCTED = "pos.inventory.stock-deducted.v1";
    public static final String INVENTORY_LOW_STOCK = "pos.inventory.low-stock.v1";
    public static final String INVENTORY_STOCK_VALUED = "pos.inventory.stock-valued.v1";
    public static final String INVENTORY_BATCH_EXPIRING = "pos.inventory.batch-expiring.v1";
    public static final String INVENTORY_NEGATIVE_STOCK_DETECTED =
            "pos.inventory.negative-stock-detected.v1";
    public static final String INVENTORY_ADJUSTMENT_POSTED = "pos.inventory.adjustment-posted.v1";

    // --- customers ------------------------------------------------------------
    public static final String CUSTOMERS_LOYALTY_ACCRUED = "pos.customers.loyalty-accrued.v1";
    public static final String CUSTOMERS_TIER_CHANGED = "pos.customers.tier-changed.v1";

    /**
     * The event type carried in the envelope, derived from the topic name so the two can never
     * drift apart: {@code pos.auth.otp-requested.v1} yields {@code auth.otp-requested}.
     */
    public static String eventTypeOf(String topic) {
        requireTopic(topic);
        String withoutPrefix = topic.startsWith("pos.") ? topic.substring(4) : topic;
        int v = withoutPrefix.lastIndexOf(".v");
        return v > 0 ? withoutPrefix.substring(0, v) : withoutPrefix;
    }

    /** The schema version encoded in a topic name, e.g. 1 for {@code ...v1}. */
    public static int schemaVersionOf(String topic) {
        requireTopic(topic);
        int v = topic.lastIndexOf(".v");
        if (v < 0) {
            throw new IllegalArgumentException("topic carries no version suffix: " + topic);
        }
        try {
            return Integer.parseInt(topic.substring(v + 2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("topic carries no version suffix: " + topic, e);
        }
    }

    private static void requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
    }

    /** The dead-letter topic paired with {@code topic}. */
    public static String dlt(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (topic.endsWith(DLT_SUFFIX)) {
            throw new IllegalArgumentException("already a dead-letter topic: " + topic);
        }
        return topic + DLT_SUFFIX;
    }
}
