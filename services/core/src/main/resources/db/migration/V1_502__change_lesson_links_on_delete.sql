-- substitution: скасована зміна більше не тримає заняття. Коли публікація нової версії видаляє
-- майбутнє заняття старої версії, на яке посилалась лише скасована зміна, посилання очищується
-- (знімки дисципліни, викладача й аудиторії в самій зміні лишаються). Заняття, на які посилаються
-- чинні зміни, публікація не видаляє (LessonMaterializationRepository.deleteReplaceable).
ALTER TABLE change
    DROP CONSTRAINT fk_change_original_lesson,
    ADD CONSTRAINT fk_change_original_lesson FOREIGN KEY (original_lesson_id) REFERENCES lesson (id)
        ON DELETE SET NULL,
    DROP CONSTRAINT fk_change_swap_lesson,
    ADD CONSTRAINT fk_change_swap_lesson FOREIGN KEY (swap_lesson_id) REFERENCES lesson (id) ON DELETE SET NULL;
