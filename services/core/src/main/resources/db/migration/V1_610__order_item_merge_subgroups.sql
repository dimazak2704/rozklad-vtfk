-- order: рядок «Об'єднання підгруп» (MERGE_SUBGROUPS, див. V1_520)

ALTER TABLE order_item DROP CONSTRAINT ck_order_item_row_type;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_row_type CHECK (row_type IN ('REPLACEMENT', 'CANCELLATION',
                                                                                 'ADDITIONAL', 'BLOCK', 'FINISHED',
                                                                                 'AS_SCHEDULED', 'ROOM_CHANGE',
                                                                                 'MERGE_SUBGROUPS'));
