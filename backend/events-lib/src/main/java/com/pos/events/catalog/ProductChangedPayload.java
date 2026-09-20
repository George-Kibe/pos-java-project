package com.pos.events.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product was created or changed.
 *
 * <p>Carries the few fields other services keep a local copy of - inventory needs the name and unit
 * to render a stock line, reporting needs the category to group by - so nothing has to call catalog
 * on a hot path. Deliberately not the whole product: a payload that mirrors every column becomes a
 * second schema to keep in step.
 */
public record ProductChangedPayload(
        UUID productId,
        String sku,
        String name,
        UUID categoryId,
        String categoryCode,
        String unitOfMeasure,
        String taxClassCode,
        boolean sellByWeight,
        boolean active,
        BigDecimal basePrice,
        String currency) {}
