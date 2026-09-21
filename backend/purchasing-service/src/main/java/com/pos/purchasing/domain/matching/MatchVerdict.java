package com.pos.purchasing.domain.matching;

/** What the three-way match concluded. */
public enum MatchVerdict {
    /** Invoice, receipt and order agree exactly. */
    MATCHED,

    /**
     * They disagree by less than the agreed tolerance. Payable without a human, because chasing a
     * supplier over two shillings costs more than the two shillings.
     */
    WITHIN_TOLERANCE,

    /**
     * Beyond tolerance. Never payable on its own: someone either corrects it or accepts it with a
     * recorded reason.
     */
    EXCEPTION
}
