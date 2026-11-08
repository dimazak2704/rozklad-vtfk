-- Orders imported from paper (POST /api/admin/import/changes) are history only: the system never rendered or
-- distributed them, so they have no DOCX/PDF/PNG and their publication time is the import time.
ALTER TABLE order_document
    ADD COLUMN imported boolean NOT NULL DEFAULT false;

-- Earlier imports: published orders with rows, none of which comes from a change of the system.
UPDATE order_document o
SET imported = true
WHERE o.status = 'PUBLISHED'
  AND EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id)
  AND NOT EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id AND i.change_id IS NOT NULL);
