-- order: примітки, відредаговані диспетчером, більше не перезаписуються примітками з календаря
ALTER TABLE order_document
    ADD COLUMN notes_edited boolean NOT NULL DEFAULT false;

-- одна чернетка доповнення на дату в семестрі (основне розпорядження вже має власний унікальний індекс)
CREATE UNIQUE INDEX uq_order_document_amendment_draft ON order_document (semester_id, date)
    WHERE kind = 'AMENDMENT' AND status = 'DRAFT';
