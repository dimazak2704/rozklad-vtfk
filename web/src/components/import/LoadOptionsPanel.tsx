import { Save } from 'lucide-react';
import { useState } from 'react';
import { IMPORT_OPTION_LIMITS, useSaveImportOptions, type ImportJob, type ImportOptions } from '@/api/import';
import { isApiError } from '@/api/errors';
import { FormAlert } from '@/components/catalog/FormParts';
import { Button, Field, NumberInput, notify } from '@/components/ui';
import { OPTIONS_LOCATION } from './decisions';
import { GroupCodesPicker } from './GroupCodesPicker';

const FIELDS: readonly string[] = ['options.groupCodes', 'options.hoursPerWeek'];

/**
 * Параметри застосування програми чи навчального плану: групи, яким додати години, і (для програми)
 * години на тиждень. Їх виправляють тут, а не завантажуючи файл ще раз: сервер одразу перераховує звіт.
 * Параметри, що визначають розбір файлу (семестр плану), лишаються як були.
 */
export function LoadOptionsPanel({ job }: { job: ImportJob }) {
  const save = useSaveImportOptions(job.id);
  const [groupCodes, setGroupCodes] = useState<string[]>(job.options?.groupCodes ?? []);
  const [hoursPerWeek, setHoursPerWeek] = useState<number | null>(job.options?.hoursPerWeek ?? null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const program = job.kind === 'DISCIPLINE_PROGRAM';
  const editable = (job.status === 'NEEDS_REVIEW' || job.status === 'READY' || job.status === 'FAILED') && job.version !== undefined;
  const issues = job.report.filter((row) => row.severity === 'ERROR' && row.location === OPTIONS_LOCATION);
  const dirty =
    groupCodes.join('|') !== (job.options?.groupCodes ?? []).join('|') || hoursPerWeek !== (job.options?.hoursPerWeek ?? null);

  const submit = async () => {
    if (job.version === undefined) return;
    setErrors({});
    setProblem(null);
    const options: ImportOptions = { ...job.options, groupCodes };
    if (program && hoursPerWeek !== null) options.hoursPerWeek = hoursPerWeek;
    else delete options.hoursPerWeek;
    try {
      const updated = await save.mutateAsync({ version: job.version, options });
      notify.success(updated.canApply ? 'Параметри збережено — імпорт готовий до застосування' : 'Параметри збережено');
    } catch (error) {
      if (isApiError(error) && error.errors.length > 0) {
        setErrors(error.fieldErrors());
        const other = error.errors.filter((e) => !FIELDS.includes(e.field)).map((e) => e.message);
        setProblem(other.length > 0 ? other.join(' ') : null);
      } else {
        setProblem(isApiError(error) ? error.message : 'Не вдалося зберегти параметри. Спробуйте ще раз.');
      }
    }
  };

  return (
    <section aria-label="Параметри застосування" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
      <div className="flex flex-col gap-0.5">
        <h3 className="text-heading font-semibold text-primary">Параметри застосування</h3>
        <p className="text-caption text-secondary">
          Кому додати години{program ? ' і скільки годин на тиждень' : ''}. Зміни перерахують звіт без повторного завантаження.
        </p>
      </div>
      {issues.length > 0 ? (
        <ul className="flex flex-col gap-1 text-ui font-medium text-danger">
          {issues.map((row) => (
            <li key={row.message}>{row.message}</li>
          ))}
        </ul>
      ) : null}
      <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_minmax(0,14rem)]">
        <Field label="Групи" required error={errors['options.groupCodes']}>
          <GroupCodesPicker value={groupCodes} onChange={setGroupCodes} disabled={!editable || save.isPending} />
        </Field>
        {program ? (
          <Field label="Годин на тиждень" error={errors['options.hoursPerWeek']} hint="Якщо програма цього не каже">
            <NumberInput
              value={hoursPerWeek}
              onValueChange={setHoursPerWeek}
              min={IMPORT_OPTION_LIMITS.hoursPerWeek.min}
              max={IMPORT_OPTION_LIMITS.hoursPerWeek.max}
              step={0.5}
              precision={1}
              disabled={!editable || save.isPending}
              className="w-32"
            />
          </Field>
        ) : null}
      </div>
      <FormAlert message={problem} />
      {editable ? (
        <Button
          variant="secondary"
          className="self-start"
          loading={save.isPending}
          disabled={!dirty}
          iconStart={<Save aria-hidden="true" className="size-4" />}
          onClick={() => void submit()}
        >
          Зберегти параметри
        </Button>
      ) : null}
    </section>
  );
}
