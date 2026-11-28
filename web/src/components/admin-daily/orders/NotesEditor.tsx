import { Plus, X } from 'lucide-react';
import { useId, useLayoutEffect, useState, type FormEvent } from 'react';
import { useUpdateOrderNotes } from '@/api/admin-daily';
import { isApiError } from '@/api/errors';
import type { OrderDto, Uuid } from '@/api/types';
import { Button, IconButton, notify, Textarea } from '@/components/ui';
import { useElementRegistry } from '@/hooks/useElementRegistry';
import { track } from '@/lib/undo/actions';
import { JournalBlockedError } from '@/lib/undo/errors';

const MAX_NOTE = 500;

/**
 * Чи є незбережені правки в редакторі, який зараз показує ці примітки. Після збереження батько
 * перемальовує редактор (новий `key`), тож запис журналу не може питати свій, уже знятий екземпляр:
 * той назавжди лишився б «зміненим» і блокував скасування. Питаємо той, що на екрані зараз.
 */
const unsavedEditors = new Map<Uuid, () => boolean>();
const hasUnsavedNotes = (orderId: Uuid): boolean => unsavedEditors.get(orderId)?.() ?? false;

/**
 * Примітки внизу розпорядження (практика, дистанційне навчання, аудиторії). Календарні примітки сервер
 * підставляє сам; диспетчер може їх уточнити, додати чи прибрати до публікації.
 * Батько задає `key` за id і текстом приміток: нова чернетка з сервера — свіжий стан редактора.
 */
export function NotesEditor({ order }: { order: OrderDto }) {
  const update = useUpdateOrderNotes();
  const [notes, setNotes] = useState<string[]>(order.notes);
  const [errors, setErrors] = useState<Record<number, string>>({});
  const baseId = useId();
  const fields = useElementRegistry();

  const dirty = notes.length !== order.notes.length || notes.some((note, index) => note !== order.notes[index]);
  // скасування з журналу оновить розпорядження й перемалює редактор — незбережене не губимо мовчки
  useLayoutEffect(() => {
    const isDirty = () => dirty;
    unsavedEditors.set(order.id, isDirty);
    return () => {
      if (unsavedEditors.get(order.id) === isDirty) unsavedEditors.delete(order.id);
    };
  }, [order.id, dirty]);

  const addNote = () => {
    setNotes((prev) => [...prev, '']);
    // фокус — у нове поле: пробіл чи Enter далі пишуть текст, а не додають ще порожніх приміток
    const next = String(notes.length);
    requestAnimationFrame(() => fields.get(next)?.focus());
  };

  const save = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!dirty || update.isPending) return;
    const local: Record<number, string> = {};
    notes.forEach((note, index) => {
      if (note.trim().length > MAX_NOTE) local[index] = `Не довше ${MAX_NOTE} символів`;
    });
    setErrors(local);
    if (Object.keys(local).length > 0) return;
    try {
      const before = order.notes;
      const after = notes.map((n) => n.trim()).filter(Boolean);
      const saved = await update.mutateAsync({ id: order.id, notes: after, version: order.version });
      // у журнал дій: Ctrl+Z повертає попередні примітки з версією після збереження
      let version = saved.version;
      const put = async (next: string[]) => {
        if (hasUnsavedNotes(order.id)) throw new JournalBlockedError('Спершу збережіть або скасуйте зміни в примітках', 'failed');
        version = (await update.mutateAsync({ id: order.id, notes: next, version })).version;
      };
      track({ label: 'Змінено примітки розпорядження', undo: () => put(before), redo: () => put(after) });
    } catch (error) {
      if (isApiError(error)) {
        const server: Record<number, string> = {};
        for (const { field, message } of error.errors) {
          const match = /^notes\[(\d+)\]$/.exec(field);
          if (match) server[Number(match[1])] = message;
        }
        setErrors(server);
      }
      notify.error(error, 'Не вдалося зберегти примітки');
    }
  };

  return (
    <form noValidate aria-label="Примітки розпорядження" onSubmit={(event) => void save(event)} className="flex flex-col gap-3">
      {notes.length === 0 ? (
        <p className="text-caption text-muted">Приміток немає. Календарні (практика, дистанційне навчання) зʼявляються самі.</p>
      ) : (
        <ol className="flex flex-col gap-2">
          {notes.map((note, index) => {
            const id = `${baseId}-note-${index}`;
            return (
              <li key={index} className="flex flex-col gap-1">
                <div className="flex items-start gap-1.5">
                  <label htmlFor={id} className="sr-only">
                    Примітка {index + 1}
                  </label>
                  <Textarea
                    id={id}
                    ref={fields.register(String(index))}
                    rows={2}
                    value={note}
                    aria-invalid={errors[index] ? true : undefined}
                    onChange={(event) => setNotes((prev) => prev.map((n, i) => (i === index ? event.target.value : n)))}
                    className="min-h-14"
                  />
                  <IconButton
                    label={`Прибрати примітку ${index + 1}`}
                    icon={<X aria-hidden="true" className="size-4" />}
                    size="sm"
                    onClick={() => setNotes((prev) => prev.filter((_, i) => i !== index))}
                  />
                </div>
                {errors[index] ? <p className="text-caption font-medium text-danger">{errors[index]}</p> : null}
              </li>
            );
          })}
        </ol>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="ghost" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={addNote}>
          Додати примітку
        </Button>
        {dirty ? (
          <>
            <Button size="sm" variant="secondary" onClick={() => setNotes(order.notes)} disabled={update.isPending}>
              Скасувати зміни
            </Button>
            <Button type="submit" size="sm" variant="secondary" loading={update.isPending}>
              Зберегти примітки
            </Button>
          </>
        ) : null}
      </div>
    </form>
  );
}
