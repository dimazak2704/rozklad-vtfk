import { Plus, X } from 'lucide-react';
import { useId, useState } from 'react';
import { useUpdateOrderNotes } from '@/api/admin-daily';
import { isApiError } from '@/api/errors';
import type { OrderDto } from '@/api/types';
import { Button, IconButton, notify, Textarea } from '@/components/ui';

const MAX_NOTE = 500;

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

  const dirty = notes.length !== order.notes.length || notes.some((note, index) => note !== order.notes[index]);

  const save = async () => {
    const local: Record<number, string> = {};
    notes.forEach((note, index) => {
      if (note.trim().length > MAX_NOTE) local[index] = `Не довше ${MAX_NOTE} символів`;
    });
    setErrors(local);
    if (Object.keys(local).length > 0) return;
    try {
      await update.mutateAsync({ id: order.id, notes: notes.map((n) => n.trim()).filter(Boolean) });
      notify.success('Примітки збережено');
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
    <div className="flex flex-col gap-3">
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
        <Button size="sm" variant="ghost" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => setNotes((prev) => [...prev, ''])}>
          Додати примітку
        </Button>
        {dirty ? (
          <>
            <Button size="sm" variant="secondary" onClick={() => setNotes(order.notes)} disabled={update.isPending}>
              Скасувати зміни
            </Button>
            <Button size="sm" variant="secondary" loading={update.isPending} onClick={() => void save()}>
              Зберегти примітки
            </Button>
          </>
        ) : null}
      </div>
    </div>
  );
}
