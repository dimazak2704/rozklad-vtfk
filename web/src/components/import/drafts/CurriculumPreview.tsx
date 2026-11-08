import { TriangleExclamationPoint } from 'lucide-react';
import type { CurriculumDraft } from '@/api/import';
import { CONTROL_FORM_LABEL } from '@/components/load/labels';

/** Розпізнані рядки навчального плану одного семестру: години на тиждень і за семестр. */
export function CurriculumPreview({ draft }: { draft: CurriculumDraft }) {
  const sum = draft.rows.reduce((total, row) => total + (row.hoursPerWeek ?? 0), 0);
  return (
    <div className="flex flex-col gap-3">
      <p className="text-ui text-secondary">
        {draft.semester} семестр{draft.weeks ? ` · ${draft.weeks} тижнів` : ''}
        {draft.totalHoursPerWeek ? ` · у плані ${draft.totalHoursPerWeek} год на тиждень` : ''}
      </p>
      {draft.warnings.length > 0 ? (
        <ul className="flex flex-col gap-1 rounded-control border border-change bg-change-subtle px-3 py-2 text-caption text-change">
          {draft.warnings.map((warning) => (
            <li key={warning} className="flex items-start gap-1.5">
              <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
              {warning}
            </li>
          ))}
        </ul>
      ) : null}
      <div className="overflow-x-auto rounded-panel border border-default bg-surface">
        <table className="w-full min-w-[36rem] border-separate border-spacing-0 text-ui">
          <caption className="sr-only">Рядки навчального плану</caption>
          <thead>
            <tr className="bg-sunken text-caption text-secondary">
              <th scope="col" className="border-b border-default px-3 py-2 text-start font-semibold">
                Дисципліна
              </th>
              <th scope="col" className="border-b border-default px-3 py-2 text-end font-semibold">
                Год/тиж
              </th>
              <th scope="col" className="border-b border-default px-3 py-2 text-end font-semibold">
                За семестр
              </th>
              <th scope="col" className="border-b border-default px-3 py-2 text-end font-semibold">
                Лекції
              </th>
              <th scope="col" className="border-b border-default px-3 py-2 text-end font-semibold">
                Лаб.
              </th>
              <th scope="col" className="border-b border-default px-3 py-2 text-start font-semibold">
                Контроль
              </th>
            </tr>
          </thead>
          <tbody>
            {draft.rows.map((row) => (
              <tr key={row.discipline}>
                <th scope="row" className="border-b border-separator px-3 py-1.5 text-start font-normal">
                  {row.discipline}
                </th>
                <td className="border-b border-separator px-3 py-1.5 text-end figures">{row.hoursPerWeek ?? '—'}</td>
                <td className="border-b border-separator px-3 py-1.5 text-end figures">{row.hoursPerSemester ?? '—'}</td>
                <td className="border-b border-separator px-3 py-1.5 text-end figures">{row.lectures ?? '—'}</td>
                <td className="border-b border-separator px-3 py-1.5 text-end figures">{row.labs ?? '—'}</td>
                <td className="border-b border-separator px-3 py-1.5 text-secondary">
                  {row.controlForm === 'UNKNOWN' ? 'не розпізнано' : CONTROL_FORM_LABEL[row.controlForm]}
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="font-semibold">
              <th scope="row" className="px-3 py-2 text-start">
                Разом розпізнано
              </th>
              <td className="px-3 py-2 text-end figures">{sum}</td>
              <td colSpan={4} />
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}
