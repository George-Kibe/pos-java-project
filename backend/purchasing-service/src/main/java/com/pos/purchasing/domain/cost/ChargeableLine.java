package com.pos.purchasing.domain.cost;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One received line as the allocator sees it.
 *
 * <p>Deliberately not the JPA entity: the allocator is arithmetic over values, and taking an entity
 * would drag a persistence context into every test of it.
 *
 * @param goodsValue what the supplier charged for this line, before delivery charges
 */
public record ChargeableLine(UUID lineId, BigDecimal quantity, BigDecimal goodsValue) {}
