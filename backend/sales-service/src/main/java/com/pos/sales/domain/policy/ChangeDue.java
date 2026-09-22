package com.pos.sales.domain.policy;

import java.math.BigDecimal;
import java.util.List;

/**
 * What to hand back, and in what.
 *
 * @param denominations largest first, so a cashier counts out the way people actually do
 * @param unpayableRemainder what the drawer cannot make up from the denominations it holds; zero in
 *     any sane currency configuration, and worth knowing about when it is not
 */
public record ChangeDue(
        BigDecimal amount, List<DenominationCount> denominations, BigDecimal unpayableRemainder) {

    public record DenominationCount(BigDecimal denomination, int count) {}

    public boolean isExact() {
        return amount.signum() == 0;
    }
}
