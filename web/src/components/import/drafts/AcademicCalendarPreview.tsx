import { RefreshCw } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { useSaveImportOptions, type AcademicCalendarDraft, type CoreCalendarImportResult, type ImportJob } from '@/api/import';
import { errorMessage } from '@/api/errors';
import { FormAlert } from '@/components/catalog/FormParts';
import { EFFECT_LABEL } from '@/components/calendar/labels';
import { Button, Chip, notify } from '@/components/ui';
import { formatNumericDate } from '@/lib/date';
import { countLabel } from '@/lib/labels';

/** Скільки рядків періодів показувати одразу: у графіку коледжу їх сотні, решту видно після «Показати всі». */
const FIRST_ROWS = 60;

const ACTION_LABEL: Record<CoreCalendarImportResult['periods'][number]['action'], string> = {
  CREATE: 'новий',
  UPDATE: 'оновиться',
  UNCHANGED: 'без змін',
  DELETE: 'видалиться',
  SKIP_MANUAL: 'змінено вручну',
};

/** «Буде створено 3, оновлено 1, видалено 0, без змін 12.» — з останньої перевірки core. */
function checkSentence(check: CoreCalendarImportResult): string {
  const lessons = check.lessons.removed > 0 || check.lessons.added > 0
    ? ` Пари від сьогодні: зникне ${check.lessons.removed}, повернеться ${check.lessons.added}.`
    : '';
  return `Буде створено ${check.created}, оновлено ${check.updated}, видалено ${check.deleted}, без змін ${check.unchanged}.${lessons}`;
}

/** «4 курс», «1, 2 курси». */
function coursesText(courses: readonly number[] | null): string {
  const list = courses ?? [];
  return `${list.join(', ')} ${list.length === 1 ? 'курс' : 'курси'}`;
}

function groupsText(codes: readonly string[] | null): string {
  if (!codes || codes.length === 0) return '';
  return codes.length > 3 ? `${codes.slice(0, 3).join(', ')} та ще ${codes.length - 3}` : codes.join(', ');
}

/**
 * Чернетка графіка освітнього процесу: які коди клітинок розпізнано (і які пропустити), які періоди підуть у
 * календар і що з ними зробить core за останньою перевіркою.
 */
export function AcademicCalendarPreview({ job, draft }: { job: ImportJob; draft: AcademicCalendarDraft }) {
  const [all, setAll] = useState(false);
  const actions = new Map((draft.check?.periods ?? []).map((p) => [p.sourceKey, p.action]));
  // період цілком поза семестрами системи core не зберігає: у «Що буде» так і пишемо, а не «—»
  const outside = new Set((draft.check?.outOfSemester ?? []).filter((o) => o.kept.length === 0).map((o) => o.sourceKey));
  const rows = all ? draft.periods : draft.periods.slice(0, FIRST_ROWS);
  return (
    <div className="flex flex-col gap-4">
      <p className="text-ui text-secondary">
        {draft.academicYear} · аркуш «{draft.sheet}» · {countLabel(draft.groups.length, ['група', 'групи', 'груп'])} ·{' '}
        {countLabel(draft.periods.length, ['період', 'періоди', 'періодів'])}
      </p>
      {draft.check ? (
        <p className="text-ui font-semibold text-primary">{checkSentence(draft.check)}</p>
      ) : (
        <p className="text-ui text-change">Календар ще не перевірив цей графік: core був недоступний. Перевірте знову трохи згодом.</p>
      )}
      <CalendarCodesPanel job={job} draft={draft} />
      <div className="overflow-x-auto rounded-panel border border-default bg-surface">
        <table className="w-full min-w-[40rem] border-separate border-spacing-0 text-ui">
          <caption className="sr-only">Періоди з графіка освітнього процесу</caption>
          <thead>
            <tr className="bg-sunken text-caption text-secondary">
              {['Код', 'Назва', 'Дати', 'Кого', 'Що буде'].map((title) => (
                <th key={title} scope="col" className="border-b border-default px-3 py-2 text-start font-semibold">
                  {title}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((period) => {
              const action = actions.get(period.sourceKey);
              return (
                <tr key={period.sourceKey}>
                  <td className="border-b border-separator px-3 py-2 font-semibold">{period.kindCode}</td>
                  <td className="border-b border-separator px-3 py-2">
                    {period.title}
                    {period.roomName ? <span className="text-secondary">, ауд. {period.roomName}</span> : null}
                  </td>
                  <td className="border-b border-separator px-3 py-2 whitespace-nowrap figures">
                    {formatNumericDate(period.startsOn)} – {formatNumericDate(period.endsOn)}
                  </td>
                  <td className="border-b border-separator px-3 py-2">
                    {period.scope === 'ALL' ? 'Усі групи' : period.scope === 'COURSES' ? coursesText(period.courses) : groupsText(period.groupCodes)}
                  </td>
                  <td className="border-b border-separator px-3 py-2 text-secondary">
                    {action ? ACTION_LABEL[action] : outside.has(period.sourceKey) ? 'поза семестром, не імпортується' : '—'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {draft.periods.length > FIRST_ROWS && !all ? (
        <Button variant="ghost" size="sm" className="self-start" onClick={() => setAll(true)}>
          Показати всі {draft.periods.length}
        </Button>
      ) : null}
    </div>
  );
}

/**
 * Коди клітинок графіка: розпізнані — з видом і ефектом, невідомі — з порадою додати вид. Будь-який код можна
 * пропустити (`options.skipCodes`); «Перевірити знову» зберігає параметри, і importer перебудовує звіт — так
 * само після того, як вид додали в «Видах періодів».
 */
function CalendarCodesPanel({ job, draft }: { job: ImportJob; draft: AcademicCalendarDraft }) {
  const save = useSaveImportOptions(job.id);
  const [skip, setSkip] = useState<string[]>(job.options?.skipCodes ?? []);
  const [problem, setProblem] = useState<string | null>(null);
  const editable = (job.status === 'NEEDS_REVIEW' || job.status === 'READY' || job.status === 'FAILED') && job.version !== undefined;
  const unknown = draft.codes.filter((c) => !c.kindCode && !skip.includes(c.code));

  const recheck = async () => {
    if (job.version === undefined) return;
    setProblem(null);
    try {
      const updated = await save.mutateAsync({ version: job.version, options: { ...job.options, skipCodes: skip } });
      notify.success(updated.canApply ? 'Графік перевірено — можна застосувати' : 'Графік перевірено');
    } catch (error) {
      setProblem(errorMessage(error));
    }
  };

  return (
    <section aria-label="Коди графіка" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
      <div className="flex flex-col gap-0.5">
        <h3 className="text-heading font-semibold text-primary">Коди графіка</h3>
        <p className="text-caption text-secondary">
          {unknown.length > 0 ? (
            <>
              Невідомі коди: {unknown.map((c) => `«${c.code}»`).join(', ')}. Додайте вид у{' '}
              <Link to="/admin/period-kinds" className="font-semibold text-accent underline-offset-2 hover-fine:underline">
                «Видах періодів»
              </Link>{' '}
              або пропустіть код (натисніть його нижче), потім перевірте знову.
            </>
          ) : (
            'Усі коди розпізнано. Щоб пропустити код, натисніть його: його клітинки не імпортуються.'
          )}
        </p>
      </div>
      <FormAlert message={problem} />
      <ul className="flex flex-wrap gap-1.5" aria-label="Коди клітинок">
        {draft.codes.map((code) => {
          const skipped = skip.includes(code.code);
          return (
            <li key={code.code}>
              <Chip
                selected={!skipped}
                disabled={!editable}
                aria-pressed={!skipped}
                onToggle={() => setSkip(skipped ? skip.filter((c) => c !== code.code) : [...skip, code.code])}
              >
                <span className="font-semibold">{code.code}</span>{' '}
                <span className="text-secondary">
                  {code.kindName ? `${code.kindName}${code.effect ? ` · ${EFFECT_LABEL[code.effect].toLocaleLowerCase('uk')}` : ''}` : 'невідомий'}
                  {skipped ? ' · пропущено' : ''} · {code.cells}
                </span>
              </Chip>
            </li>
          );
        })}
      </ul>
      {editable ? (
        <Button
          size="sm"
          variant="secondary"
          className="self-start"
          loading={save.isPending}
          iconStart={<RefreshCw aria-hidden="true" className="size-4" />}
          onClick={() => void recheck()}
        >
          Перевірити знову
        </Button>
      ) : null}
    </section>
  );
}
