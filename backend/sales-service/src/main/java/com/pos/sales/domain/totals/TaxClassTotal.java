package com.pos.sales.domain.totals;

import java.math.BigDecimal;

/**
 * One row of the receipt's tax breakdown.
 *
 * <p>Per tax class rather than one total, because that is what a VAT return is built from and what
 * a customer querying their receipt is shown. A basket of zero-rated flour and standard-rated soap
 * has to show both.
 */
public record TaxClassTotal(
        String taxClassCode,
        BigDecimal taxRate,
        BigDecimal net,
        BigDecimal tax,
        BigDecimal gross) {}
