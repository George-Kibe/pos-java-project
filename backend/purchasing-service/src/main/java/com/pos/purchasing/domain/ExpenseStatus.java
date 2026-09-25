package com.pos.purchasing.domain;

/** Only an approved expense counts against profit. */
public enum ExpenseStatus {
    /** Above the approval limit: waits for someone other than whoever recorded it. */
    PENDING_APPROVAL,
    APPROVED,
    /** Refused by the approver, for a reason. */
    REJECTED,
    /** Entered in error, for a reason; kept, never deleted. */
    VOIDED
}
