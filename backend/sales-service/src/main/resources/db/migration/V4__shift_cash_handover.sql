-- Closing a shift is a handover: the cashier returns the drawer's cash to a supervisor or branch
-- manager, who confirms with their PIN what they received, and only then can the shift be closed.
-- The amount received is the shift's counted cash; the approver is recorded beside it, and the
-- notes go into the branch's intraday cash (intraday_movements, kind TILL_CLOSE).
--
-- Adds nullable columns only, so it is safe with the previous version still running.

ALTER TABLE till_sessions ADD COLUMN handed_over_cash NUMERIC(19,4);
ALTER TABLE till_sessions ADD COLUMN handed_over_at   TIMESTAMPTZ;
ALTER TABLE till_sessions ADD COLUMN handed_over_to   UUID;

ALTER TABLE till_sessions ADD CONSTRAINT till_sessions_handed_over_cash_check
    CHECK (handed_over_cash IS NULL OR handed_over_cash >= 0);
