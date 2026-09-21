package com.pos.purchasing.domain.matching;

import java.math.BigDecimal;
import java.util.List;

/**
 * The outcome of matching one invoice.
 *
 * @param invoicedTotal what the supplier is asking for
 * @param justifiedTotal what the receipt and the order between them support
 * @param variance {@code invoicedTotal - justifiedTotal}; positive means over-billed
 */
public record MatchResult(
        MatchVerdict verdict,
        BigDecimal invoicedTotal,
        BigDecimal justifiedTotal,
        BigDecimal variance,
        List<LineVariance> variances) {

    public boolean isPayable() {
        return verdict != MatchVerdict.EXCEPTION;
    }

    /** True when the supplier is asking for more than the delivery justifies. */
    public boolean isOverBilled() {
        return variance.signum() > 0;
    }
}
