import { Plus, Save, Trash2, Undo2 } from 'lucide-react';
import { useState } from 'react';
import { type OrderDraft, type OrderDraftRow, useSaveOrderDraft } from '@/api/import';
import { isApiError } from '@/api/errors';
import type { OrderKind, OrderRowType, Uuid } from '@/api/types';
import { Button, Field, IconButton, Input, Select, Textarea, notify } from '@/components/ui';

/** Типи рядків розпорядження (DOMAIN §8); «за текстом» — сервер визначить тип сам. */
const ROW_TYPES: ReadonlyArray<{ value: OrderRowType | 'AUTO'; label: string }> = [
  { value: 'AUTO', label: 'За текстом' },
  { value: 'REPLACEMENT', label: 'Заміна' },
  { value: 'CANCELLATION', label: 'Пари не буде' },
  { value: 'ADDITIONAL', label: 'Додатково' },
  { value: 'BLOCK', label: 'Блок пар' },
  { value: 'FINISHED', label: 'Вичитано' },
  { value: 'AS_SCHEDULED', label: 'За розкладом' },
  { value: 'ROOM_CHANGE', label: 'Заміна аудиторії' },
];

type TextField = 'groupCode' | 'pairLabel' | 'fromDiscipline' | 'fromTeacher' | 'reasonText' | 'toDiscipline' | 'toTeacher' | 'roomText';

const COLUMNS: ReadonlyArray<{ field: TextField; label: string; width: string }> = [
  { field: 'groupCode', label: 'Група', width: 'w-20' },
  { field: 'pairLabel', label: 'Пара', width: 'w-14' },
  { field: 'fromDiscipline', label: 'За розкладом: дисципліна', width: 'w-28' },
  { field: 'fromTeacher', label: 'За розкладом: викладач', width: 'w-32' },
  { field: 'reasonText', label: 'Причина', width: 'w-24' },
  { field: 'toDiscipline', label: 'По заміні: дисципліна', width: 'w-28' },
  { field: 'toTeacher', label: 'По заміні: викладач', width: 'w-32' },
  { field: 'roomText', label: 'Ауд', width: 'w-16' },
];

interface EditableRow extends OrderDraftRow {
  key: number;
  /** Тип обрав адміністратор; AUTO — сервер визначить за текстом. */
  chosenType: OrderRowType | 'AUTO';
}

const blank = (text: string | null | undefined) => (text && text.trim() !== '' ? text.trim() : null);

const KINDS: ReadonlyArray<{ value: OrderKind; label: string }> = [
  { value: 'ORDER', label: 'Розпорядження' },
  { value: 'AMENDMENT', label: 'Доповнення' },
];

/** Номер із поля: порожньо — не розпізнано (сервер не дасть застосувати), інакше ціле число. */
const parseNumber = (text: string): number | null => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null);

/**
 * Звірка розпорядження, прочитаного ШІ з фото: заголовок (вид, номер, дата) і кожен рядок можна виправити,
 * рядок — додати чи видалити, а потім зберегти. Лише звірене розпорядження з номером і датою можна застосувати
 * (сервер тримає імпорт у NEEDS_REVIEW, доки бракує заголовка або рядки не збережено).
 */
export function OrderDraftEditor({ jobId, version, draft, onDone }: { jobId: Uuid; version: number; draft: OrderDraft; onDone: () => void }) {
  const save = useSaveOrderDraft(jobId);
  const [nextKey, setNextKey] = useState(draft.rows.length);
  const [rows, setRows] = useState<EditableRow[]>(() =>
    draft.rows.map((row, index) => ({ ...row, key: index, chosenType: row.rowType })),
  );
  const [notes, setNotes] = useState(draft.notes.join('\n'));
  // заголовок теж читає ШІ: без номера й дати core розпорядження не прийме
  const [number, setNumber] = useState(draft.number != null ? String(draft.number) : '');
  const [date, setDate] = useState(draft.date ?? '');
  const [kind, setKind] = useState<OrderKind>(draft.kind);
  const [parentNumber, setParentNumber] = useState(draft.parentNumber != null ? String(draft.parentNumber) : '');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const update = (key: number, patch: Partial<EditableRow>) =>
    setRows((current) => current.map((row) => (row.key === key ? { ...row, ...patch } : row)));

  const addRow = () => {
    setRows((current) => [
      ...current,
      { key: nextKey, section: 'CHANGES', groupCode: '', pairLabel: '', rowType: 'REPLACEMENT', chosenType: 'AUTO' },
    ]);
    setNextKey((key) => key + 1);
  };

  const submit = async () => {
    setErrors({});
    try {
      await save.mutateAsync({
        version,
        number: parseNumber(number),
        kind,
        parentNumber: kind === 'AMENDMENT' ? parseNumber(parentNumber) : null,
        date: date === '' ? null : date,
        weekdayLabel: draft.weekdayLabel ?? null,
        parityLabel: draft.parityLabel ?? null,
        rows: rows.map((row) => ({
          section: row.chosenType === 'ROOM_CHANGE' ? 'ROOM_CHANGES' : 'CHANGES',
          groupCode: row.groupCode.trim(),
          pairLabel: row.pairLabel.trim(),
          rowType: row.chosenType === 'AUTO' ? null : row.chosenType,
          fromDiscipline: blank(row.fromDiscipline),
          fromTeacher: blank(row.fromTeacher),
          reasonText: blank(row.reasonText),
          toDiscipline: blank(row.toDiscipline),
          toTeacher: blank(row.toTeacher),
          roomText: blank(row.roomText),
        })),
        notes: notes.split('\n').map((note) => note.trim()).filter((note) => note !== ''),
        signatoryPosition: draft.signatoryPosition ?? null,
        signatoryName: draft.signatoryName ?? null,
      });
      notify.success('Рядки збережено', { description: 'Звірене розпорядження можна застосувати.' });
      onDone();
    } catch (error) {
      if (isApiError(error) && error.errors.length > 0) setErrors(error.fieldErrors());
      notify.error(error, 'Не вдалося зберегти рядки');
    }
  };

  return (
    <section aria-label="Звірка рядків розпорядження" className="flex flex-col gap-3">
      <p className="text-ui text-secondary">
        Порівняйте кожен рядок з фото: виправте групу, пару, прізвища й аудиторії, видаліть зайве. Тип «За текстом» сервер
        визначить за правилами розпорядження («Вичит.» без заміни — вичитано, діапазон пар — блок).
      </p>
      <fieldset className="grid gap-3 sm:grid-cols-4">
        <legend className="mb-2 text-ui font-semibold text-primary">Заголовок розпорядження</legend>
        <Field label="Вид" error={errors.kind}>
          <Select<OrderKind> value={kind} onValueChange={setKind} options={KINDS} />
        </Field>
        <Field label="Номер" required error={errors.number}>
          <Input inputMode="numeric" value={number} onChange={(event) => setNumber(event.target.value)} />
        </Field>
        <Field label="Дата" required error={errors.date}>
          <Input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        </Field>
        {kind === 'AMENDMENT' ? (
          <Field label="Доповнює розпорядження №" required error={errors.parentNumber}>
            <Input inputMode="numeric" value={parentNumber} onChange={(event) => setParentNumber(event.target.value)} />
          </Field>
        ) : null}
      </fieldset>
      <div className="overflow-x-auto rounded-plate border border-default">
        <table className="w-full min-w-[64rem] border-collapse text-caption">
          <caption className="sr-only">Рядки розпорядження для звірки</caption>
          <thead>
            <tr className="bg-sunken text-start">
              <th scope="col" className="px-2 py-1.5 text-start font-semibold">
                №
              </th>
              {COLUMNS.map((column) => (
                <th key={column.field} scope="col" className="px-1 py-1.5 text-start font-semibold">
                  {column.label}
                </th>
              ))}
              <th scope="col" className="px-1 py-1.5 text-start font-semibold">
                Тип
              </th>
              <th scope="col" className="px-1 py-1.5">
                <span className="sr-only">Дії</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.key} className="border-t border-separator align-top">
                <td className="px-2 py-1.5 text-muted figures">{index + 1}</td>
                {COLUMNS.map((column) => {
                  const error = errors[`rows[${index}].${column.field}`];
                  return (
                    <td key={column.field} className="px-1 py-1">
                      <Input
                        size="sm"
                        className={column.width}
                        aria-label={`${column.label}, рядок ${index + 1}`}
                        aria-invalid={error ? true : undefined}
                        title={error}
                        value={row[column.field] ?? ''}
                        onChange={(event) => update(row.key, { [column.field]: event.target.value })}
                      />
                    </td>
                  );
                })}
                <td className="px-1 py-1">
                  <Select<OrderRowType | 'AUTO'>
                    size="sm"
                    aria-label={`Тип, рядок ${index + 1}`}
                    value={row.chosenType}
                    onValueChange={(value) => update(row.key, { chosenType: value })}
                    options={ROW_TYPES}
                    className="w-36"
                  />
                </td>
                <td className="px-1 py-1">
                  <IconButton
                    label={`Видалити рядок ${index + 1}`}
                    size="sm"
                    icon={<Trash2 aria-hidden="true" className="size-4" />}
                    onClick={() => setRows((current) => current.filter((item) => item.key !== row.key))}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Button size="sm" variant="ghost" className="self-start" onClick={addRow} iconStart={<Plus aria-hidden="true" className="size-4" />}>
        Додати рядок
      </Button>
      <label className="flex flex-col gap-1 text-ui font-semibold text-primary">
        Примітки (кожна з нового рядка)
        <Textarea rows={3} value={notes} onChange={(event) => setNotes(event.target.value)} />
      </label>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" loading={save.isPending} onClick={() => void submit()} iconStart={<Save aria-hidden="true" className="size-4" />}>
          Зберегти звірені рядки
        </Button>
        <Button variant="secondary" disabled={save.isPending} onClick={onDone} iconStart={<Undo2 aria-hidden="true" className="size-4" />}>
          Скасувати
        </Button>
      </div>
    </section>
  );
}
