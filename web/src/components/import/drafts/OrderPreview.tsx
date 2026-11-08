import { TriangleExclamationPoint } from 'lucide-react';
import type { OrderDraft, OrderDraftRow } from '@/api/import';
import { formatLongDate, isIsoDate } from '@/lib/date';
import { cn } from '@/lib/cn';

const CELL = 'border border-[var(--border-color-strong)] px-1.5 py-1 align-top';

/**
 * Розпізнане фото розпорядження — у тій самій розкладці, що й документ коледжу (DOMAIN §8): заголовок,
 * таблиця «За розкладом / Причина / По заміні», примітки жирним, підпис. Аркуш лишається світлим у
 * темній темі, щоб його було легко звірити з фото.
 */
export function OrderPreview({ draft }: { draft: OrderDraft }) {
  const changes = draft.rows.filter((row) => row.section === 'CHANGES');
  const rooms = draft.rows.filter((row) => row.section === 'ROOM_CHANGES');
  const title = draft.kind === 'AMENDMENT' ? `ДОПОВНЕННЯ до розпорядження №${draft.parentNumber ?? '—'}` : `РОЗПОРЯДЖЕННЯ №${draft.number ?? '—'}`;

  return (
    <div className="flex flex-col gap-3">
      {(draft.warnings ?? []).length > 0 ? (
        <ul className="flex flex-col gap-1 rounded-control border border-change bg-change-subtle px-3 py-2 text-caption text-change" aria-label="Зауваження розпізнавання">
          {(draft.warnings ?? []).map((warning) => (
            <li key={warning} className="flex items-start gap-1.5">
              <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
              {warning}
            </li>
          ))}
        </ul>
      ) : null}
      <article data-theme="light" aria-label={`Розпізнане ${title}`} className="overflow-x-auto rounded-plate border border-default bg-surface px-5 py-5 text-primary">
        <header className="mb-3 flex flex-col items-center gap-0.5 text-center">
          <p className="text-heading font-bold tracking-[0.06em]">{title}</p>
          <p className="text-ui font-semibold">По коледжу про деякі зміни в розкладі занять</p>
          <p className="text-ui font-semibold">На {draft.date && isIsoDate(draft.date) ? `${formatLongDate(draft.date)} р.` : '—'}</p>
          <p className="text-ui font-bold">
            {draft.weekdayLabel ?? '—'} - {draft.parityLabel ?? '—'}
          </p>
        </header>
        <table className="w-full min-w-[40rem] border-collapse text-caption">
          <caption className="sr-only">Рядки розпорядження</caption>
          <thead>
            <tr>
              <th rowSpan={2} scope="col" className={cn(CELL, 'w-14 font-semibold')}>
                Група
              </th>
              <th rowSpan={2} scope="col" className={cn(CELL, 'w-10 font-semibold')}>
                Пара
              </th>
              <th colSpan={2} scope="colgroup" className={cn(CELL, 'font-semibold')}>
                За розкладом
              </th>
              <th rowSpan={2} scope="col" className={cn(CELL, 'w-16 font-semibold')}>
                Причина заміни
              </th>
              <th colSpan={2} scope="colgroup" className={cn(CELL, 'font-semibold')}>
                По заміні
              </th>
              <th rowSpan={2} scope="col" className={cn(CELL, 'w-12 font-semibold')}>
                Ауд
              </th>
            </tr>
            <tr>
              <th scope="col" className={cn(CELL, 'font-normal text-secondary')}>дисципліна</th>
              <th scope="col" className={cn(CELL, 'font-normal text-secondary')}>викладач</th>
              <th scope="col" className={cn(CELL, 'font-normal text-secondary')}>дисципліна</th>
              <th scope="col" className={cn(CELL, 'font-normal text-secondary')}>викладач</th>
            </tr>
          </thead>
          <tbody>
            {changes.map((row, index) => (
              <OrderRow key={index} row={row} />
            ))}
          </tbody>
        </table>
        <p className="mt-3 text-center text-ui font-semibold tracking-[0.04em]">ЗАМІНА АУДИТОРІЙ</p>
        {rooms.length > 0 ? (
          <ul className="mt-1 flex flex-col gap-0.5 text-caption">
            {rooms.map((row, index) => (
              <li key={index}>
                {row.groupCode}, {row.pairLabel} пара — ауд. {row.roomText ?? '—'}
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-center text-caption text-muted">— розділ порожній —</p>
        )}
        {draft.notes.length > 0 ? (
          <ul className="mt-4 flex flex-col gap-1 text-ui font-bold">
            {draft.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        ) : null}
        <p className="mt-5 flex flex-wrap justify-between gap-4 text-ui font-bold">
          <span>{draft.signatoryPosition ?? ''}</span>
          <span>{draft.signatoryName ?? ''}</span>
        </p>
      </article>
    </div>
  );
}

function OrderRow({ row }: { row: OrderDraftRow }) {
  const left =
    row.rowType === 'ADDITIONAL' && !row.fromDiscipline ? (
      <td colSpan={2} className={cn(CELL, 'text-center font-semibold text-secondary')}>
        ДОДАТКОВО
      </td>
    ) : row.rowType === 'AS_SCHEDULED' ? (
      <td colSpan={2} className={cn(CELL, 'text-center')}>
        За розкладом
      </td>
    ) : (
      <>
        <td className={CELL}>{row.fromDiscipline ?? ''}</td>
        <td className={CELL}>{row.fromTeacher ?? ''}</td>
      </>
    );
  const right =
    row.rowType === 'CANCELLATION' ? (
      <td colSpan={2} className={cn(CELL, 'text-center font-semibold text-cancel')}>
        Пари не буде
      </td>
    ) : (
      <>
        <td className={CELL}>{row.toDiscipline ?? ''}</td>
        <td className={CELL}>{row.toTeacher ?? ''}</td>
      </>
    );
  return (
    <tr className={row.rowType === 'BLOCK' ? 'hatch' : undefined}>
      <th scope="row" className={cn(CELL, 'text-start font-semibold')}>
        {row.groupCode}
      </th>
      <td className={cn(CELL, 'figures')}>{row.pairLabel}</td>
      {left}
      <td className={CELL}>{row.reasonText ?? ''}</td>
      {right}
      <td className={cn(CELL, 'figures')}>{row.roomText ?? ''}</td>
    </tr>
  );
}
