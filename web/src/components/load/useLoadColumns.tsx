import { useMemo } from 'react';
import type { LoadItem } from '@/api/load';
import type { GroupTheoryDto, LoadSource } from '@/api/types';
import { planHint } from '@/components/accounting/theory';
import { useTeacherLabel } from '@/components/admin-daily/useTeacherLabel';
import { Tooltip, type DataTableColumn } from '@/components/ui';
import { disciplineLine } from '@/lib/discipline-color';
import { LESSON_KIND_LABEL } from '@/lib/labels';
import { CONTROL_FORM_LABEL, LOAD_SOURCE_HINT, LOAD_SOURCE_LABEL } from './labels';
import { formatHours, pairsPerWeek, patternDescription, patternShort } from './pattern';

/** Звичайне джерело годин: у колонці «Джерело» його не повторюємо — лише винятки. */
export const DEFAULT_LOAD_SOURCE: LoadSource = 'TIMETABLE_DERIVED';

/** Другий рядок дисципліни: повна назва, вид занять, форма контролю, поділ на підгрупи. */
function disciplineNote(item: LoadItem): string {
  const parts: string[] = [];
  if (item.discipline.name !== item.discipline.shortName) parts.push(item.discipline.name);
  if (item.lessonKind !== 'MIXED') parts.push(LESSON_KIND_LABEL[item.lessonKind]);
  if (item.controlForm !== 'NONE') parts.push(CONTROL_FORM_LABEL[item.controlForm].toLocaleLowerCase('uk'));
  if (item.splitSubgroups) parts.push('за підгрупами');
  return parts.join(' · ');
}

interface LoadColumnsInput {
  /** Обрана група: тоді колонки «Група» немає. */
  groupId: string | null;
  /** Перші рядки груп на сторінці (сортування за групою): код групи — лише в них. */
  groupStarts: ReadonlySet<string> | null;
  /** Тижні теорії обраної групи — для підказки до плану «За семестр». */
  groupTheory: GroupTheoryDto | undefined;
}

/** Колонки таблиці навантаження: група, дисципліна, години, пари тижня, план за семестр, викладачі, джерело. */
export function useLoadColumns({ groupId, groupStarts, groupTheory }: LoadColumnsInput) {
  const name = useTeacherLabel();
  return useMemo<Array<DataTableColumn<LoadItem>>>(
    () => [
      ...(groupId
        ? []
        : [
            {
              id: 'group',
              header: 'Група',
              width: '6rem',
              sortValue: (item: LoadItem) => item.group.code,
              cell: (item: LoadItem) =>
                !groupStarts || groupStarts.has(item.id) ? (
                  <span className="figures font-semibold">{item.group.code}</span>
                ) : (
                  <span className="sr-only">{item.group.code}</span>
                ),
            } satisfies DataTableColumn<LoadItem>,
          ]),
      {
        id: 'discipline',
        header: 'Дисципліна',
        rowHeader: true,
        width: '34%',
        sortValue: (item) => item.discipline.shortName,
        cell: (item) => (
          <span className="flex min-w-0 flex-col">
            <span className="flex items-center gap-2">
              <span data-line={disciplineLine(item.discipline)} aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
              <span className="text-cell font-[650]">{item.discipline.shortName}</span>
            </span>
            {/* повна назва й особливості одним тихим рядком, а не трьома «табличками» */}
            {disciplineNote(item) ? (
              <span className="line-clamp-1 ps-4 text-caption font-normal text-muted" title={disciplineNote(item)}>
                {disciplineNote(item)}
              </span>
            ) : null}
          </span>
        ),
      },
      {
        id: 'hours',
        header: 'Год/тиж',
        numeric: true,
        width: '6rem',
        sortValue: (item) => item.hoursPerWeek,
        // на планшеті стовпця «Пари» немає: години й так кажуть головне, а шаблон тижня — у формі рядка
        cell: (item) => formatHours(item.hoursPerWeek),
      },
      {
        // пари словами («1 щотижня + 1 через тиждень»), а не гліфами; пояснення про чисельник — у підказці
        id: 'pattern',
        header: 'Пари',
        width: '14rem',
        hideBelow: 'lg',
        className: 'whitespace-nowrap max-md:hidden!',
        sortValue: (item) => pairsPerWeek(item.pattern),
        cell: (item) => (
          <Tooltip content={patternDescription(item.pattern)}>
            <span
              tabIndex={0}
              className="rounded-tag text-ui text-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
            >
              {patternShort(item.pattern)}
            </span>
          </Tooltip>
        ),
      },
      {
        id: 'planned',
        header: 'За семестр',
        numeric: true,
        width: '7rem',
        // на планшеті — без нього: план за семестр видно у формі й обліку годин
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        sortValue: (item) => item.plannedHours,
        // задане число — з позначкою: система його не перераховує, на відміну від автоматичного плану
        cell: (item) => (
          <Tooltip content={planHint(item, groupTheory)}>
            <span
              tabIndex={0}
              className="inline-flex flex-col items-end rounded-tag focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
            >
              <span>{item.plannedHours} год</span>
              {item.planSource === 'FIXED' ? <span className="text-caption text-muted">задано</span> : null}
            </span>
          </Tooltip>
        ),
      },
      {
        id: 'teachers',
        header: 'Викладачі',
        hideBelow: 'md',
        sortValue: (item) => item.teachers[0]?.teacher.shortName,
        cell: (item) =>
          item.teachers.length === 0 ? (
            <span className="text-caption text-change">Не призначено</span>
          ) : (
            <span className="flex flex-col">
              {item.teachers.map((t) => (
                <span key={t.subgroup} className="whitespace-nowrap">
                  {t.subgroup > 0 ? <span className="text-caption text-muted">{t.subgroup} п. · </span> : null}
                  {name(t.teacher)}
                </span>
              ))}
            </span>
          ),
      },
      {
        id: 'source',
        header: 'Джерело',
        // на планшеті джерело видно у фільтрі «Джерело», а ширина потрібна викладачам
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        sortValue: (item) => LOAD_SOURCE_LABEL[item.source],
        // звичайне джерело («З розкладу») не повторюємо в кожному рядку — лише винятки
        cell: (item) =>
          item.source === DEFAULT_LOAD_SOURCE && !item.note ? null : (
          <Tooltip content={item.note ? `${LOAD_SOURCE_HINT[item.source]} ${item.note}` : LOAD_SOURCE_HINT[item.source]}>
            <span tabIndex={0} className="inline-flex rounded-tag text-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus">
              {LOAD_SOURCE_LABEL[item.source]}
            </span>
          </Tooltip>
          ),
      },
    ],
    [groupId, groupStarts, name, groupTheory],
  );
}
