import { zodResolver } from '@hookform/resolvers/zod';
import { Plus } from 'lucide-react';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { isApiError, withConflicts } from '@/api/errors';
import { periodKindRequest, usePeriodKindImpact, useSavePeriodKind, useDeletePeriodKind } from '@/api/period-kinds';
import { qk } from '@/api/queryKeys';
import type { PeriodEffect, PeriodKindDto, PeriodKindImpactDto, PeriodKindRequest } from '@/api/types';
import { applyServerErrors, blankToNull } from '@/components/catalog/forms';
import { FormAlert, FormDialog } from '@/components/catalog/FormParts';
import { Button, Checkbox, Chip, Field, Input, NumberInput, RadioGroup, Textarea } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { trackFinal } from '@/lib/undo/actions';
import { skipUnchanged, trackSavedRecord } from '@/lib/undo/records';
import { confirm } from '@/stores/confirm';
import { EFFECT_LABEL, EFFECT_OPTIONS } from './labels';
import { NOTE_PLACEHOLDERS, renderNoteTemplate, T_SUSPEND } from './notes';
import { KindImpactSummary } from './KindImpact';
import { impactProblems } from './kind-impact';

const CODE_RE = /^[\p{L}\p{N}]{1,10}$/u;
const HEX_RE = /^#[0-9a-f]{6}$/i;

/** Кольори з легенди графіка освітнього процесу 2026/27: канікули, виробнича практика, атестація, навчальна практика. */
const LEGEND_COLORS: ReadonlyArray<{ hex: string; name: string }> = [
  { hex: '#FFFFCC', name: 'світло-жовтий (канікули)' },
  { hex: '#33CCCC', name: 'бірюзовий (виробнича практика)' },
  { hex: '#CCFFCC', name: 'світло-зелений (атестація)' },
  { hex: '#FF6600', name: 'помаранчевий (навчальна практика)' },
];

const kindSchema = z.object({
  code: z.string().trim().regex(CODE_RE, 'Код — від 1 до 10 літер чи цифр, без пробілів'),
  name: z.string().trim().min(1, 'Назвіть вид: «Національний спротив», «Збори»').max(100, 'Не довше за 100 символів'),
  effect: z.enum(['DAY_OFF', 'SUSPEND', 'ONLINE', 'INFO'], { error: 'Оберіть, що вид робить із парами' }),
  color: z.string().nullable().refine((value) => value === null || HEX_RE.test(value), 'Колір — у вигляді #RRGGBB'),
  aliases: z.array(z.string()).max(10, 'Не більше 10 псевдонімів'),
  noteTemplate: z.string().trim().max(500, 'Не довше за 500 символів'),
  active: z.boolean(),
  position: z.number({ error: 'Порядок — ціле число' }).int('Ціле число').min(0, 'Не менше 0').max(9999, 'Не більше 9999').nullable(),
});

type KindValues = z.infer<typeof kindSchema>;
const FIELDS = ['code', 'name', 'effect', 'color', 'aliases', 'noteTemplate', 'active', 'position'] as const;

interface PeriodKindFormDialogProps {
  /** null — новий вид. */
  kind: PeriodKindDto | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Приклад примітки за шаблоном: 4КІ1, 16–27 листопада, назва періоду — назва виду. */
function sampleNote(template: string, values: Pick<KindValues, 'name' | 'effect'>): string {
  return renderNoteTemplate(template, {
    template,
    kindName: values.name,
    title: values.name || 'Назва періоду',
    startsOn: '2026-11-16',
    endsOn: '2026-11-27',
    scope: 'GROUPS',
    courses: [],
    groupCodes: ['4КІ1'],
    studyMode: values.effect === 'ONLINE' ? 'ONLINE' : null,
    roomName: values.effect === 'SUSPEND' ? '401' : null,
  }).text;
}

/**
 * Вид періоду: код як у графіку, назва, ефект, колір, псевдоніми й шаблон примітки. Зміну ефекту виду з
 * періодами спершу перевіряє сервер (`dryRun`): форма показує, що зміниться, і питає підтвердження.
 */
export function PeriodKindFormDialog({ kind, open, onOpenChange }: PeriodKindFormDialogProps) {
  const formId = useId();
  const save = useSavePeriodKind();
  const remove = useDeletePeriodKind();
  const impact = usePeriodKindImpact();
  const [formError, setFormError] = useState<string | null>(null);
  const [aliasDraft, setAliasDraft] = useState('');
  const [aliasError, setAliasError] = useState<string | null>(null);
  const [defaults] = useState<KindValues>(() => ({
    code: kind?.code ?? '',
    name: kind?.name ?? '',
    effect: kind?.effect ?? 'SUSPEND',
    color: kind?.color ?? null,
    aliases: kind?.aliases ?? [],
    noteTemplate: kind?.noteTemplate ?? '',
    active: kind?.active ?? true,
    position: kind?.position ?? null,
  }));
  const form = useForm<KindValues>({ resolver: zodResolver(kindSchema), defaultValues: defaults });
  const { errors, isDirty } = form.formState;
  const values = useWatch({ control: form.control }) as KindValues;
  const effectChanged = kind !== null && values.effect !== kind.effect;

  const toBody = (data: KindValues): PeriodKindRequest => ({
    code: data.code.trim(),
    name: data.name.trim(),
    effect: data.effect,
    color: data.color ? data.color.toUpperCase() : null,
    aliases: data.aliases,
    noteTemplate: blankToNull(data.noteTemplate),
    active: data.active,
    ...(data.position === null ? {} : { position: data.position }),
    version: kind?.version,
  });

  const persist = async (body: PeriodKindRequest) => {
    const saved = await save.mutateAsync({ id: kind?.id, body });
    if (kind && body.effect !== kind.effect) {
      // зміна ефекту перебудовує пари всіх періодів виду: повертають її так само, з перевіркою, а не Ctrl+Z
      trackFinal(`Змінено ефект виду «${saved.code}»: ${EFFECT_LABEL[saved.effect].toLocaleLowerCase('uk')}`, 'Ефект змінюють знову у «Видах періодів»');
    } else {
      trackSavedRecord<PeriodKindRequest>({
        kind: 'period-kinds',
        label: kind ? `Змінено вид періоду «${saved.code}»` : `Додано вид періоду «${saved.code}»`,
        invalidate: [qk.admin.calendar.all],
        before: kind ? periodKindRequest(kind) : null,
        after: body,
        saved,
        create: (next) => save.mutateAsync({ body: next }),
        update: (id, next) => save.mutateAsync({ id, body: next }),
        remove: (id) => remove.mutateAsync(id),
      });
    }
    onOpenChange(false);
  };

  /**
   * Ефект змінено: спершу пробне збереження. Відмова — у формі; без періодів — просто зберігаємо; інакше діалог
   * з тим, що зміниться, і збереження з нього (кнопка чекає на сервер).
   */
  const changeEffect = async (current: PeriodKindDto, body: PeriodKindRequest): Promise<void> => {
    const result: PeriodKindImpactDto = await impact.mutateAsync({ id: current.id, body });
    if (!result.canApply) {
      setFormError(impactProblems(result));
      return;
    }
    if (result.periods === 0) {
      await persist(body);
      return;
    }
    await confirm({
      title: `Змінити ефект виду «${current.code}»?`,
      description: <KindImpactSummary impact={result} from={current.effect} />,
      confirmLabel: 'Змінити ефект',
      // 409 з живими замінами лишається в діалозі разом із самими замінами
      onConfirm: () =>
        persist(body).catch((error: unknown) => {
          throw isApiError(error) ? new Error(withConflicts(error)) : error;
        }),
    });
  };

  const submit = form.handleSubmit(async (data) => {
    setFormError(null);
    try {
      const body = toBody(data);
      if (skipUnchanged(kind ? periodKindRequest(kind) : null, body, () => onOpenChange(false))) return;
      if (kind && body.effect !== kind.effect) await changeEffect(kind, body);
      else await persist(body);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS, { 'aliases[0]': 'aliases' }));
    }
  });

  const addAlias = () => {
    const alias = aliasDraft.trim();
    if (!CODE_RE.test(alias)) {
      setAliasError('Псевдонім — від 1 до 10 літер чи цифр, без пробілів.');
      return;
    }
    const current = form.getValues('aliases');
    const lower = alias.toLocaleLowerCase('uk');
    if (current.some((a) => a.toLocaleLowerCase('uk') === lower) || form.getValues('code').trim().toLocaleLowerCase('uk') === lower) {
      setAliasError(`«${alias}» уже є.`);
      return;
    }
    form.setValue('aliases', [...current, alias], { shouldDirty: true, shouldValidate: true });
    setAliasDraft('');
    setAliasError(null);
  };

  const template = values.noteTemplate?.trim() ?? '';
  const preview = sampleNote(template || T_SUSPEND, values);

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={kind ? `${kind.code} · ${kind.name}` : 'Новий вид періоду'}
      description={
        kind
          ? kind.usage > 0
            ? `Вид мають ${countLabel(kind.usage, ['період', 'періоди', 'періодів'])} календаря: назва й колір зміняться в усіх.`
            : 'Періодів цього виду ще немає.'
          : 'Код — як у графіку освітнього процесу: імпорт графіка розпізнає вид саме за ним.'
      }
      formId={formId}
      submitLabel={kind ? 'Зберегти' : 'Додати вид'}
      pending={save.isPending || impact.isPending}
      dirty={isDirty}
      size="xl"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_19rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <FormAlert message={formError} />
          <div className="grid gap-4 sm:grid-cols-[8rem_minmax(0,1fr)]">
            <Field
              label="Код"
              required
              error={errors.code?.message}
              hint={kind?.builtin ? 'Код вбудованого виду не змінюється' : 'Як у графіку: НС, ЗУ'}
            >
              <Input {...form.register('code')} autoComplete="off" spellCheck={false} disabled={kind?.builtin} autoFocus={!kind} />
            </Field>
            <Field label="Назва" required error={errors.name?.message} hint={`Її бачать сайт і бот: «${values.name?.trim() || 'Національний спротив'}: назва періоду»`}>
              <Input {...form.register('name')} autoComplete="off" />
            </Field>
          </div>
          <Controller
            control={form.control}
            name="effect"
            render={({ field }) => (
              <div className="flex flex-col gap-1.5">
                <RadioGroup<PeriodEffect> legend="Що робить із парами" value={field.value} onValueChange={field.onChange} options={EFFECT_OPTIONS} />
                {errors.effect ? <p className="text-caption font-medium text-danger">{errors.effect.message}</p> : null}
                {effectChanged && kind.usage > 0 ? (
                  <p className="rounded-control border border-change bg-change-subtle px-3 py-2 text-caption text-primary">
                    Зміна ефекту перебудує пари {countLabel(kind.usage, ['періоду', 'періодів', 'періодів'])} цього виду від сьогодні. Перед
                    збереженням покажемо, що саме зміниться.
                  </p>
                ) : null}
              </div>
            )}
          />
          <Controller
            control={form.control}
            name="color"
            render={({ field }) => <KindColorField value={field.value} onChange={(next) => field.onChange(next)} error={errors.color?.message} />}
          />
          <Controller
            control={form.control}
            name="aliases"
            render={({ field }) => (
              <Field
                label="Псевдоніми"
                error={aliasError ?? errors.aliases?.message}
                hint="Інші написання коду в графіку: у легенді «З», у клітинках «ЗУ»."
              >
                <div className="flex flex-col gap-2">
                  {field.value.length > 0 ? (
                    <ul className="flex flex-wrap gap-1.5" aria-label="Псевдоніми виду">
                      {field.value.map((alias) => (
                        <li key={alias}>
                          <Chip
                            onRemove={() => field.onChange(field.value.filter((a) => a !== alias))}
                            removeLabel={`Прибрати псевдонім ${alias}`}
                          >
                            {alias}
                          </Chip>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  <div className="flex gap-2">
                    <Input
                      size="sm"
                      value={aliasDraft}
                      aria-label="Новий псевдонім"
                      placeholder="Напр., З"
                      autoComplete="off"
                      spellCheck={false}
                      className="max-w-40"
                      onChange={(event) => {
                        setAliasDraft(event.target.value);
                        if (aliasError) setAliasError(null);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault();
                          addAlias();
                        }
                      }}
                    />
                    <Button size="sm" variant="secondary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={addAlias}>
                      Додати
                    </Button>
                  </div>
                </div>
              </Field>
            )}
          />
          <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
            <Controller
              control={form.control}
              name="active"
              render={({ field }) => (
                <Checkbox
                  checked={field.value}
                  onCheckedChange={(checked) => field.onChange(checked === true)}
                  label="Можна вибрати для нових періодів"
                  description="Вимкнений вид лишається в наявних періодах."
                />
              )}
            />
            <Field label="Порядок у списку" error={errors.position?.message} className="w-36">
              <Controller
                control={form.control}
                name="position"
                render={({ field }) => <NumberInput value={field.value} onValueChange={field.onChange} min={0} max={9999} step={10} />}
              />
            </Field>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-3 lg:border-s lg:border-separator lg:ps-5">
          <Field
            label="Шаблон примітки"
            error={errors.noteTemplate?.message}
            hint={template ? 'Так «Скласти» у формі періоду й імпорт графіка пишуть примітку розпорядження.' : 'Порожньо — імпорт не пише примітку, а «Скласти» бере шаблон практики.'}
          >
            <Textarea {...form.register('noteTemplate')} rows={3} spellCheck={false} placeholder={T_SUSPEND} />
          </Field>
          <details className="group text-caption text-secondary">
            <summary className="cursor-pointer rounded-tag font-semibold text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus">
              Поля шаблону
            </summary>
            <dl className="mt-1.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-2 gap-y-0.5">
              {NOTE_PLACEHOLDERS.map((item) => (
                <div key={item.token} className="contents">
                  <dt className="font-mono text-primary">{item.token}</dt>
                  <dd>{item.meaning}</dd>
                </div>
              ))}
            </dl>
            <p className="mt-1.5">У квадратних дужках — необовʼязкове: «[, ауд. {'{ауд}'}]» зникає, якщо аудиторії немає.</p>
          </details>
          <figure className="flex flex-col gap-1.5">
            <figcaption className="text-caption text-muted">Приклад для 4КІ1, 16–27 листопада</figcaption>
            <div data-theme="light" className="rounded-tag border border-default bg-surface px-4 py-3 text-primary">
              <div aria-hidden="true" className="mb-2.5 h-px bg-[var(--border-color-strong)]" />
              <p className="text-ui font-bold break-words">{preview}</p>
            </div>
          </figure>
        </div>
      </form>
    </FormDialog>
  );
}

/** Колір виду: без кольору, один з кольорів легенди графіка або свій `#RRGGBB`. */
function KindColorField({ value, onChange, error }: { value: string | null; onChange: (value: string | null) => void; error?: string }) {
  const name = useId();
  const legend = LEGEND_COLORS.find((c) => c.hex.toLowerCase() === value?.toLowerCase());
  const [custom, setCustom] = useState(value && !legend ? value : '');
  const option = (selected: boolean) =>
    cn(
      'relative grid size-8 cursor-pointer place-items-center rounded-full border-2',
      'has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-focus',
      selected ? 'border-accent' : 'border-transparent hover-fine:border-strong',
    );
  return (
    <fieldset className="flex min-w-0 flex-col gap-2">
      <legend className="mb-1 text-ui font-semibold text-primary">Колір</legend>
      <div className="flex flex-wrap items-center gap-1.5">
        <label
          className={cn(
            'inline-flex h-8 cursor-pointer items-center rounded-full border px-3 text-caption font-semibold select-none',
            'has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-focus',
            value === null ? 'border-accent bg-selected text-accent' : 'border-default text-secondary hover-fine:bg-hover hover-fine:text-primary',
          )}
        >
          <input type="radio" name={name} checked={value === null} onChange={() => onChange(null)} className="sr-only" />
          Без кольору
        </label>
        {LEGEND_COLORS.map((color) => {
          const checked = legend?.hex === color.hex;
          return (
            <label key={color.hex} title={color.name} className={option(checked)}>
              <input type="radio" name={name} checked={checked} onChange={() => onChange(color.hex)} aria-label={`Колір: ${color.name}`} className="sr-only" />
              {/* вибраний колір — кільце навколо (колір графіка сталий в обох темах, тож галочку на ньому не видно) */}
              <span aria-hidden="true" className="size-5 rounded-full border border-strong" style={{ backgroundColor: color.hex }} />
            </label>
          );
        })}
        <Input
          size="sm"
          value={custom}
          aria-label="Свій колір, #RRGGBB"
          placeholder="#RRGGBB"
          maxLength={7}
          spellCheck={false}
          autoComplete="off"
          aria-invalid={Boolean(error) || (custom !== '' && !HEX_RE.test(custom)) || undefined}
          onChange={(event) => {
            const next = event.target.value.trim();
            setCustom(next);
            if (HEX_RE.test(next)) onChange(next.toUpperCase());
          }}
          className="w-28 font-mono"
        />
      </div>
      <p className="text-caption text-muted">Колір — як у легенді графіка коледжу; у розкладі він лише крапка поруч із назвою.</p>
      {error ? <p className="text-caption font-medium text-danger">{error}</p> : null}
    </fieldset>
  );
}
