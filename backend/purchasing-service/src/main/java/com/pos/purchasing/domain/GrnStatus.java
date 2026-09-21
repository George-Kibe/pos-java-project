package com.pos.purchasing.domain;

/** A goods receipt's life. Only POSTED moves stock. */
public enum GrnStatus {
    /** Being keyed in at the loading bay. Nothing has reached inventory. */
    DRAFT,

    /** Committed: landed costs allocated, the event published, stock on its way to the shelf. */
    POSTED,

    /**
     * Abandoned before posting. A posted GRN is never cancelled - the goods are in the shop, so a
     * mistake is corrected with a supplier return, not by erasing the receipt.
     */
    CANCELLED;

    public boolean isEditable() {
        return this == DRAFT;
    }
}
