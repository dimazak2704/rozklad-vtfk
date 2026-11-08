-- order: номер першого розпорядження семестру, коли облік почали вести не з початку семестру
-- (паперові розпорядження №1–34 вже були, система продовжує з №35). Без рядка нумерація з 1.
CREATE TABLE order_numbering
(
    semester_id  uuid         NOT NULL,
    first_number integer      NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(100),
    CONSTRAINT pk_order_numbering PRIMARY KEY (semester_id),
    CONSTRAINT fk_order_numbering_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT ck_order_numbering_first CHECK (first_number BETWEEN 1 AND 9999)
);
