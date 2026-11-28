import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link } from 'react-router';
import { useDeleteCalendarPeriod, useSaveCalendarPeriod, type CalendarPeriod, type CalendarPeriodRequest, type CalendarSemester } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { usePeriodKinds } from '@/api/period-kinds';
import { qk } from '@/api/queryKeys';
import type { StudyMode } from '@/api/types';
import { applyServerErrors, blankToNull } from '@/components/catalog/forms';
import { FormAlert, FormDialog } from '@/components/catalog/FormParts';
import { Field, Input, RadioGroup, Select } from '@/components/ui';
import { formatNumericDate, isIsoDate } from '@/lib/date';
import { skipUnchanged, trackSavedRecord } from '@/lib/undo/records';
import { DateInput } from './DateInput';
import { initialKindId, PERIOD_FIELDS, periodDefaults, periodSchema, type PeriodValues } from './period-form';
import { OrderNotePanel, ScopeFields, SuspendFields } from './PeriodFormFields';
import { KindSwatch } from './KindSwatch';
import { EFFECT_HINT, EFFECT_LABEL, LEGACY_KIND, STUDY_MODE_LABEL } from './labels';
import { composeOrderNote } from './notes';
import { periodRequest } from './periods';

interface PeriodFormDialogProps {
  semester: CalendarSemester;
  period: CalendarPeriod | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Період календаря: вид з довідника (канікули, практика, НС, форма навчання…), дати, кого стосується, і поля за
 * ефектом виду: режим для «онлайн», аудиторія й керівники для «група не на парах». Примітку, яку система додасть
 * у розпорядження на ці дати, складає шаблон виду — з попереднім переглядом на «аркуші».
 */
export function PeriodFormDialog({ semester, period, open, onOpenChange }: PeriodFormDialogProps) {
  const formId = useId();
  const save = useSaveCalendarPeriod();
  const remove = useDeleteCalendarPeriod();
  const kinds = usePeriodKinds();
  const groups = useCatalogOptions('groups', { active: true });
  const teachers = useCatalogOptions('teachers', { active: true });
  const rooms = useCatalogOptions('rooms', { active: true });
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<PeriodValues>({
    resolver: zodResolver(periodSchema),
    defaultValues: periodDefaults(period, kinds.data),
  });
  const { errors, isDirty } = form.formState;
  const values = useWatch({ control: form.control }) as PeriodValues;

  // вид підставляємо, коли довідник завантажився: новому періоду — практику, старому періоду — його вбудований вид.
  // resetField діє лише на зареєстроване поле, а вміст діалогу монтується після першого кадру, тому вид з кешу
  // задано вже в defaultValues
  const kindList = kinds.data;
  useEffect(() => {
    if (!kindList || form.getValues('kindId')) return;
    const id = initialKindId(period, kindList);
    if (id) form.resetField('kindId', { defaultValue: id });
  }, [kindList, period, form]);

  const kind = kindList?.find((k) => k.id === values.kindId) ?? null;
  const effect = kind?.effect ?? (period ? LEGACY_KIND[period.kind].effect : null);
  const groupById = new Map((groups.items ?? []).map((g) => [g.id, g]));
  const chosenGroups = values.groupIds.map((id) => groupById.get(id)).filter((g) => g !== undefined);
  // підпис керівника: з довідника активних, а для вже призначеного — і з самого періоду
  const supervisorNames = new Map<string, string>([
    ...(period?.supervisors ?? []).map((t): [string, string] => [t.id, t.shortName]),
    ...(teachers.items ?? []).map((t): [string, string] => [t.id, t.shortName]),
  ]);
  const chosenSupervisors = values.supervisorIds.map((id) => ({ id, name: supervisorNames.get(id) ?? '…' }));
  const roomOptions = [
    ...(period?.room && !(rooms.items ?? []).some((r) => r.id === period.room?.id) ? [{ value: period.room.id, label: period.room.name }] : []),
    ...(rooms.items ?? []).map((r) => ({ value: r.id, label: r.name })),
  ];
  const roomName = roomOptions.find((r) => r.value === values.roomId)?.label ?? null;
  const draftNote = kind
    ? composeOrderNote({
        template: kind.noteTemplate,
        kindName: kind.name,
        title: values.title ?? '',
        startsOn: isIsoDate(values.startsOn) ? values.startsOn : '',
        endsOn: isIsoDate(values.endsOn) ? values.endsOn : '',
        scope: values.scope,
        courses: values.courses,
        groupCodes: chosenGroups.map((g) => g.code),
        studyMode: effect === 'ONLINE' ? values.studyMode : null,
        roomName: effect === 'SUSPEND' ? roomName : null,
      })
    : '';

  /** Період — у журнал дій: доданий скасовує видалення, змінений — попередні значення з новою версією. */
  const journalPeriod = (before: CalendarPeriod | null, after: CalendarPeriodRequest, saved: CalendarPeriod) => {
    trackSavedRecord<CalendarPeriodRequest>({
      kind: 'periods',
      label: before ? `Змінено період «${saved.title}»` : `Додано період «${saved.title}»`,
      invalidate: [qk.admin.calendar.all],
      before: before ? periodRequest(before) : null,
      after,
      saved,
      create: (body) => save.mutateAsync({ body }),
      update: (id, body) => save.mutateAsync({ id, body }),
      remove: (id) => remove.mutateAsync(id),
    });
  };

  const submit = form.handleSubmit(async (data) => {
    setFormError(null);
    if (effect === 'ONLINE' && !data.studyMode) {
      form.setError('studyMode', { type: 'manual', message: 'Оберіть форму навчання' });
      return;
    }
    try {
      const suspend = effect === 'SUSPEND';
      const body: CalendarPeriodRequest = {
        semesterId: semester.id,
        kindId: data.kindId,
        title: data.title.trim(),
        startsOn: data.startsOn,
        endsOn: data.endsOn,
        studyMode: effect === 'ONLINE' ? data.studyMode : null,
        scope: data.scope,
        courses: data.scope === 'COURSES' ? data.courses : null,
        groupIds: data.scope === 'GROUPS' ? data.groupIds : null,
        roomId: suspend ? data.roomId : null,
        supervisorIds: suspend ? data.supervisorIds : [],
        orderNote: blankToNull(data.orderNote),
        version: period?.version,
      };
      if (skipUnchanged(period ? periodRequest(period) : null, body, () => onOpenChange(false))) return;
      const saved = await save.mutateAsync({ id: period?.id, body });
      journalPeriod(period, body, saved);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, PERIOD_FIELDS, { kind: 'kindId', 'groupIds[0]': 'groupIds', 'supervisorIds[0]': 'supervisorIds' }));
    }
  });

  // вимкнений вид не пропонуємо, але вид самого періоду лишається у списку
  const kindOptions = (kindList ?? [])
    .filter((k) => k.active || k.id === period?.kindId)
    .map((k) => ({
      value: k.id,
      label: k.name,
      description: `${k.code} · ${EFFECT_LABEL[k.effect]}${k.active ? '' : ' · вимкнено'}`,
      icon: <KindSwatch color={k.color} effect={k.effect} />,
    }));

  const groupOptions = (groups.items ?? [])
    .filter((g) => !values.groupIds.includes(g.id))
    .map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }));

  const supervisorOptions = (teachers.items ?? [])
    .filter((t) => !values.supervisorIds.includes(t.id))
    .map((t) => ({ value: t.id, label: t.shortName, keywords: [t.displayName] }));

  const kindName = kind?.name ?? (period ? LEGACY_KIND[period.kind].name : 'Вид');

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={period ? period.title : 'Новий період'}
      description={`${semester.name}: ${formatNumericDate(semester.startsOn)} – ${formatNumericDate(semester.endsOn)}`}
      formId={formId}
      submitLabel={period ? 'Зберегти' : 'Додати період'}
      pending={save.isPending}
      dirty={isDirty}
      size="xl"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_19rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <FormAlert message={formError} />
          {period?.source === 'IMPORT' ? (
            <p className="rounded-control border border-default bg-sunken px-3 py-2 text-caption text-secondary">
              Період з графіка освітнього процесу. Після збереження змін імпорт графіка його більше не оновлюватиме.
            </p>
          ) : null}
          <div className="grid gap-4 sm:grid-cols-[14rem_minmax(0,1fr)]">
            <Field
              label="Вид"
              required
              error={errors.kindId?.message ?? (kinds.isError ? 'Види періодів не завантажено. Оновіть сторінку.' : undefined)}
              hint={
                effect ? (
                  <>
                    {EFFECT_HINT[effect]}{' '}
                    <Link to="/admin/period-kinds" className="font-semibold text-accent underline-offset-2 hover-fine:underline">
                      Види періодів
                    </Link>
                  </>
                ) : undefined
              }
            >
              <Controller
                control={form.control}
                name="kindId"
                render={({ field }) => (
                  <Select<string>
                    value={field.value || null}
                    placeholder={kinds.isPending ? 'Завантажуємо…' : 'Оберіть вид'}
                    disabled={kinds.isPending || kinds.isError}
                    onValueChange={(next) => {
                      field.onChange(next);
                      const nextEffect = kindList?.find((k) => k.id === next)?.effect;
                      if (nextEffect !== 'ONLINE') form.setValue('studyMode', null);
                      if (nextEffect !== 'SUSPEND') {
                        form.setValue('supervisorIds', []);
                        form.setValue('roomId', null);
                      }
                    }}
                    options={kindOptions}
                  />
                )}
              />
            </Field>
            {/* без прикладу в полі: зразок з автофокусом читали як уже введену назву */}
            <Field label="Назва" required error={errors.title?.message} hint={period ? undefined : 'Як у розпорядженні, напр. «Основи національного спротиву»'}>
              <Input {...form.register('title')} autoComplete="off" autoFocus={!period} />
            </Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            {/* «Від», а не «З»: кирилична «З» біля поля дати читається як цифра 3 */}
            <Field label="Від" required error={errors.startsOn?.message}>
              <Controller
                control={form.control}
                name="startsOn"
                render={({ field }) => (
                  <DateInput value={field.value} onChange={field.onChange} onBlur={field.onBlur} name={field.name} min={semester.startsOn} max={semester.endsOn} />
                )}
              />
            </Field>
            <Field label="По (включно)" required error={errors.endsOn?.message}>
              <Controller
                control={form.control}
                name="endsOn"
                render={({ field }) => (
                  <DateInput value={field.value} onChange={field.onChange} onBlur={field.onBlur} name={field.name} min={semester.startsOn} max={semester.endsOn} />
                )}
              />
            </Field>
          </div>
          {effect === 'ONLINE' ? (
            <Controller
              control={form.control}
              name="studyMode"
              render={({ field }) => (
                <div className="flex flex-col gap-1">
                  <RadioGroup<StudyMode>
                    legend="Форма навчання"
                    orientation="horizontal"
                    value={field.value}
                    onValueChange={field.onChange}
                    options={(['ONLINE', 'MIXED', 'OFFLINE'] as const).map((mode) => ({ value: mode, label: STUDY_MODE_LABEL[mode] }))}
                  />
                  {errors.studyMode ? <p className="text-caption font-medium text-danger">{errors.studyMode.message}</p> : null}
                </div>
              )}
            />
          ) : null}
          <ScopeFields form={form} scope={values.scope} groupOptions={groupOptions} chosenGroups={chosenGroups} groupsLoading={groups.isPending} />
          {effect === 'SUSPEND' ? (
            <SuspendFields
              form={form}
              kindName={kindName}
              roomOptions={roomOptions}
              roomsLoading={rooms.isPending}
              supervisorOptions={supervisorOptions}
              chosenSupervisors={chosenSupervisors}
              teachersLoading={teachers.isPending}
            />
          ) : null}
        </div>

        <OrderNotePanel form={form} draftNote={draftNote} note={values.orderNote} />
      </form>
    </FormDialog>
  );
}
