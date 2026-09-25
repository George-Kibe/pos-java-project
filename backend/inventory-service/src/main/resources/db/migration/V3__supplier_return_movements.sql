-- Goods sent back to a supplier leave the shelf as their own kind of movement, SUPPLIER_RETURN, so
-- shrinkage reports can tell them from damage and write-offs.
--
-- Widens a CHECK only, so it is safe with the previous version still running.
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_type_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_type_check CHECK (
    type IN ('RECEIPT', 'SALE', 'RETURN', 'ADJUSTMENT', 'WRITE_OFF',
             'TRANSFER_OUT', 'TRANSFER_IN', 'STOCK_TAKE', 'OPENING_BALANCE', 'SUPPLIER_RETURN')
);
