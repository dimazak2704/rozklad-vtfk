import { TriangleExclamationPoint } from 'lucide-react';
import { useMemo, useState, type FormEvent } from 'react';
import { useCancelChange, useCreateChange, useTeacherAvailability } from '@/api/admin-daily';
import { useCatalogOptions } from '@/api/catalog';
import { isApiError } from '@/api/errors';
import { useLoadItems } from '@/api/load';
import { useBells } from '@/api/meta';
import { usePublicGroups, usePublicSchedule, usePublicTeachers } from '@/api/public';
import { qk } from '@/api/queryKeys';
import type { ChangeDto, IsoDate, LessonView, ReasonCode, SemesterRef, Uuid } from '@/api/types';
import { Button, Combobox, Dialog, DialogContent, Field, Input, SegmentedControl, Select, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatShortWeekdayDate, nearestStudyDay } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import { track } from '@/lib/undo/actions';
import { assignLabel, availabilityLabel, reasonForOption } from '../model';
import { useTeacherLabel } from '../useTeacherLabel';
import {
  lessonSelectable,
  loadTeachers,
  NEW_CHANGE_MODES,
  newChangeLabel,
  newChangeRequest,
  defaultTeacherDiscipline,
  teacherChangeKind,
  teacherDisciplines,
  validateNewChange,
  type NewChangeErrors,
  type NewChangeMode,
  type NewChangePrefill,
  type NewChangeValues,
} from './new-change';
import { ReasonPicker } from './ReasonPicker';

const PAIRS = [1, 2, 3, 4, 5, 6, 7, 8] as const;
const NO_LESSONS: LessonView[] = [];

interface NewChangeDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Робоча дата сторінки — дата зміни за замовчуванням. */
  date: IsoDate;
  semester: SemesterRef | null;
  /** З «Обліку годин»: вид, група й дисципліна вже обрані — диспетчер лише ставить дату й пару. */
  prefill: NewChangePrefill | null;
  approvalMode: string | null | undefined;
  onCreated?: (change: ChangeDto) => void;
}

interface FormState {
  mode: NewChangeMode;
  groupId: Uuid | null;
  date: IsoDate;
  pairs: number[];
  disciplineId: Uuid | null;
  /** null — підставляємо з навантаження (додаткова пара) чи нікого (інший викладач). */
  teacherId: Uuid | null;
  /** null — уся група або підгрупа викладача з навантаження. */
  subgroup: number | null;
  roomId: Uuid | null;
  lessonId: Uuid | null;
  /** null — причина за видом зміни. */
  reasonCode: ReasonCode | null;
  reasonText: string;
}

function initialState(date: IsoDate, prefill: NewChangePrefill | null): FormState {
  return {
    mode: prefill?.mode ?? 'additional',
    groupId: prefill?.groupId ?? null,
    // у вихідний пар немає: сторінку відкрили в неділю — форма пропонує понеділок
    date: prefill?.date ?? nearestStudyDay(date),
    pairs: [...(prefill?.pairs ?? [])],
    disciplineId: prefill?.disciplineId ?? null,
    teacherId: null,
    subgroup: null,
    roomId: prefill?.roomId ?? null,
    lessonId: prefill?.lessonId ?? null,
    reasonCode: null,
    reasonText: '',
  };
}

/**
 * «Нова зміна» — зміна розкладу без відсутності викладача: додаткова пара (відпрацювання), інша аудиторія,
 * інший викладач на пару групи, вичитана дисципліна. Пари відсутніх вирішують у списку «Відсутні» —
 * там сервер підбирає варіанти; тут диспетчер усе обирає сам.
 */
export function NewChangeDialog({ open, onOpenChange, date, semester, prefill, approvalMode, onCreated }: NewChangeDialogProps) {
  const [state, setState] = useState<FormState>(() => initialState(date, prefill));
  const [showErrors, setShowErrors] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const create = useCreateChange();
  const cancel = useCancelChange();
  const name = useTeacherLabel();

  const groups = usePublicGroups();
  const teachers = usePublicTeachers();
  const disciplines = useCatalogOptions('disciplines', {}, { enabled: open });
  const rooms = useCatalogOptions('rooms', {}, { enabled: open });
  const load = useLoadItems(open ? semester?.id : null, state.groupId);
  const bells = useBells(open ? state.date : null);
  const needsLesson = state.mode !== 'additional';
  // пари групи цього дня: для зміни пари — з чого обирати, для додаткової — які номери вже зайняті
  const schedule = usePublicSchedule('GROUP', state.groupId ?? undefined, state.date, state.date, { enabled: open && Boolean(state.groupId) });
  const dayLessons = useMemo(
    () => (schedule.data?.days ?? []).flatMap((day) => day.lessons).sort((a, b) => a.pair - b.pair || a.subgroup - b.subgroup),
    [schedule.data],
  );
  const lessons = needsLesson ? dayLessons : NO_LESSONS;
  const busyPairs = useMemo(() => {
    const out = new Map<number, string>();
    for (const l of dayLessons) {
      if (l.kind === 'CANCELLED') continue;
      const label = out.get(l.pair);
      out.set(l.pair, label && label !== l.discipline.shortName ? `${label}, ${l.discipline.shortName}` : l.discipline.shortName);
    }
    return out;
  }, [dayLessons]);

  // пара: обрана явно, а з «Обліку годин» («Вичитано») — перша пара цієї дисципліни в цей день
  const lesson =
    lessons.find((l) => l.id === state.lessonId) ??
    (state.lessonId === null && prefill?.disciplineId
      ? (lessons.find((l) => l.discipline.id === prefill.disciplineId && lessonSelectable(l, state.mode)) ?? null)
      : null);

  // додаткова пара: викладач і підгрупа — з навантаження, доки диспетчер не обрав сам
  const assigned = loadTeachers(load.data, state.groupId, state.disciplineId);
  // викладач, що вже відмовився від цієї пари, — не підставляємо його вдруге
  const declinedId = prefill?.replaces?.teacherId ?? null;
  const declinedTeacher = declinedId ? ((teachers.data ?? []).find((t) => t.id === declinedId) ?? null) : null;
  const loadSuggested = assigned.find((t) => t.subgroup === state.subgroup) ?? assigned[0] ?? null;
  const suggested = loadSuggested && loadSuggested.teacher.id !== declinedId ? loadSuggested : null;
  const own = teacherDisciplines(load.data, state.groupId, state.teacherId);

  const values: NewChangeValues =
    state.mode === 'additional'
      ? {
          mode: 'additional',
          groupId: state.groupId,
          date: state.date,
          pairs: state.pairs,
          subgroup: state.subgroup ?? loadSuggested?.subgroup ?? 0,
          disciplineId: state.disciplineId,
          teacherId: state.teacherId ?? suggested?.teacher.id ?? null,
          roomId: state.roomId,
          lesson: null,
          reasonCode: 'OTHER',
          reasonText: state.reasonText,
        }
      : {
          mode: state.mode,
          groupId: state.groupId,
          date: state.date,
          pairs: [],
          subgroup: lesson?.subgroup ?? 0,
          disciplineId: state.mode === 'teacher' ? (state.disciplineId ?? defaultTeacherDiscipline(own, lesson)) : null,
          teacherId: state.mode === 'teacher' ? state.teacherId : null,
          roomId: state.mode === 'finished' ? null : state.roomId,
          lesson,
          reasonCode: 'OTHER',
          reasonText: state.reasonText,
        };
  const teacherKind = state.mode === 'teacher' ? teacherChangeKind(values) : null;
  const reasonCode: ReasonCode = state.reasonCode ?? reasonForOption(teacherKind, undefined);
  values.reasonCode = state.mode === 'teacher' ? reasonCode : 'OTHER';
  const errors: NewChangeErrors = showErrors ? validateNewChange(values) : {};

  const availability = useTeacherAvailability(open && state.mode === 'teacher' && lesson ? lesson.id : null, '');

  const patch = (next: Partial<FormState>) => {
    setFormError(null);
    setState((prev) => ({ ...prev, ...next }));
  };
  const setMode = (mode: NewChangeMode) =>
    patch({
      mode,
      lessonId: null,
      teacherId: null,
      roomId: null,
      reasonCode: null,
      disciplineId: mode === 'additional' || mode === 'finished' ? state.disciplineId : null,
    });

  const groupOptions = useMemo(
    () =>
      (groups.data ?? []).map((g) => ({
        value: g.id,
        label: g.code,
        keywords: [g.department ?? ''],
      })),
    [groups.data],
  );
  const disciplineOptions = useMemo(() => {
    // спершу дисципліни групи з навантаження — їх і відпрацьовують
    const ofGroup = new Set((load.data ?? []).filter((item) => item.group.id === state.groupId).map((item) => item.discipline.id));
    return (disciplines.items ?? [])
      .map((d) => ({
        value: d.id,
        label: d.shortName,
        description: d.name !== d.shortName ? d.name : undefined,
        keywords: [d.name],
      }))
      .sort((a, b) => Number(ofGroup.has(b.value)) - Number(ofGroup.has(a.value)));
  }, [disciplines.items, load.data, state.groupId]);
  const teacherOptions = useMemo(
    () =>
      (teachers.data ?? []).map((t) => ({
        value: t.id,
        label: name(t),
        keywords: [t.shortName, t.displayName],
        description: t.cycleCommission ?? undefined,
      })),
    [teachers.data, name],
  );
  const freeTeacherOptions = useMemo(
    () =>
      (availability.data ?? []).map((item) => ({
        value: item.teacher.id,
        label: name(item.teacher),
        keywords: [item.teacher.shortName],
        description: availabilityLabel(item),
        disabled: item.status !== 'FREE',
      })),
    [availability.data, name],
  );
  const roomOptions = useMemo(
    () =>
      (rooms.items ?? [])
        .filter((r) => r.active)
        .map((r) => ({
          value: r.id,
          label: roomLabel(r.name) ?? r.name,
          keywords: [r.name],
        })),
    [rooms.items],
  );

  const reset = () => {
    setState(initialState(date, prefill));
    setShowErrors(false);
    setFormError(null);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setShowErrors(true);
    const body = newChangeRequest(values);
    if (!body) return;
    setFormError(null);
    try {
      const created = await create.mutateAsync(body);
      const group = groups.data?.find((g) => g.id === created.group.id)?.code ?? created.group.code;
      const label = newChangeLabel(state.mode, group, { from: created.pairFrom, to: created.pairTo }, formatShortWeekdayDate(created.date));
      track({
        label: created.status === 'PENDING_CONFIRMATION' ? `${label}: надіслано прохання в Telegram` : label,
        note: created.status === 'PENDING_CONFIRMATION' ? 'Якщо скасувати, бот повідомить викладача про скасування.' : undefined,
        invalidate: [qk.admin.all],
        undo: () => cancel.mutateAsync(created.id),
      });
      // нова зміна заміняє ту, від якої відмовились: стару прибираємо, щоб у списку не висіла «відмова»
      const replaced = prefill?.replaces?.changeId;
      if (replaced) {
        try {
          await cancel.mutateAsync(replaced);
        } catch (error) {
          notify.error(error, 'Нову зміну збережено, але стару з відмовою прибрати не вдалося — приберіть її в «Інших змінах»');
        }
      }
      onCreated?.(created);
      onOpenChange(false);
      reset();
    } catch (error) {
      if (isApiError(error) && (error.conflicts.length > 0 || error.errors.length > 0)) {
        setFormError([...error.conflicts.map((c) => c.message), ...error.errors.map((e) => e.message)].join('; '));
      } else {
        notify.error(error, 'Не вдалося зберегти зміну');
      }
    }
  };

  const modeHint = NEW_CHANGE_MODES.find((m) => m.value === state.mode)?.hint;
  const declinedName = declinedTeacher ? name(declinedTeacher) : null;
  const declinedHint = declinedName ? `Відмова: ${declinedName} — оберіть іншого` : undefined;
  const pending = create.isPending;
  const submitLabel = state.mode === 'teacher' ? assignLabel(approvalMode) : state.mode === 'additional' ? 'Додати пару' : 'Зберегти зміну';

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (pending) return;
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent
        title="Нова зміна"
        description="Зміна без відсутності: додаткова пара, інша аудиторія, інший викладач чи вичитана дисципліна. Пари відсутніх викладачів вирішуйте у списку «Відсутні» — там є підібрані варіанти."
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={pending}>
              Скасувати
            </Button>
            <Button variant="primary" type="submit" form="new-change-form" loading={pending}>
              {submitLabel}
            </Button>
          </>
        }
      >
        <form id="new-change-form" noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          {state.mode === 'additional' && prefill?.missingLessons ? (
            <p role="note" className="rounded-control bg-sunken px-3 py-2 text-ui text-primary">
              За обліком годин бракує {countLabel(prefill.missingLessons, ['пари', 'пар', 'пар'])}.
              {prefill.missingLessons > 1 ? ' Одна зміна — пари одного дня підряд; решту додайте ще однією зміною на інший день.' : null}
            </p>
          ) : null}
          {prefill?.replaces ? (
            <p role="note" className="rounded-control bg-change-subtle px-3 py-2 text-ui text-primary">
              {declinedName ?? 'Викладач'} не може провести цю пару. Оберіть іншого викладача — зміну з відмовою приберемо, щойно збережете нову.
            </p>
          ) : null}
          <div className="flex flex-col gap-1.5">
            {/* чотири види в один ряд не вміщаються на телефоні — там список вибору */}
            <SegmentedControl<NewChangeMode>
              aria-label="Що змінити"
              value={state.mode}
              onValueChange={setMode}
              options={NEW_CHANGE_MODES.map((m) => ({
                value: m.value,
                label: m.label,
              }))}
              className="self-start max-sm:hidden"
            />
            <div className="sm:hidden">
              <Select<NewChangeMode>
                aria-label="Що змінити"
                value={state.mode}
                onValueChange={setMode}
                options={NEW_CHANGE_MODES.map((m) => ({
                  value: m.value,
                  label: m.label,
                }))}
              />
            </div>
            {modeHint ? <p className="text-caption text-secondary">{modeHint}</p> : null}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Група" required error={errors.groupId}>
              <Combobox
                value={state.groupId}
                onValueChange={(groupId) =>
                  patch({
                    groupId,
                    lessonId: null,
                    teacherId: null,
                    subgroup: null,
                  })
                }
                options={groupOptions}
                loading={groups.isPending}
                placeholder="Оберіть групу"
                searchPlaceholder="Код групи, напр. 4КІ1"
              />
            </Field>
            <Field label="Дата" required error={errors.date}>
              <Input
                type="date"
                value={state.date}
                min={semester?.startsOn}
                max={semester?.endsOn}
                onChange={(event) => patch({ date: event.target.value, lessonId: null })}
                className="figures"
              />
            </Field>
          </div>

          {state.mode === 'additional' ? (
            <>
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1.5 text-ui font-semibold text-primary">
                  Пара <span className="font-normal text-muted">— одна чи кілька підряд</span>
                </legend>
                <div role="group" aria-label="Номери пар" className="flex flex-wrap gap-1.5">
                  {PAIRS.map((pair) => {
                    const on = state.pairs.includes(pair);
                    const slot = bells.data?.slots.find((s) => s.pair === pair);
                    // у групи вже є пара — не забороняємо (буває для підгрупи), але видно до збереження
                    const busy = busyPairs.get(pair);
                    return (
                      <button
                        key={pair}
                        type="button"
                        aria-pressed={on}
                        aria-label={[`${pair} пара`, slot?.startsAt, busy ? `у групи вже ${busy}` : null].filter(Boolean).join(', ')}
                        title={busy ? `У групи вже ${busy}` : undefined}
                        onClick={() =>
                          patch({
                            pairs: on ? state.pairs.filter((p) => p !== pair) : [...state.pairs, pair],
                          })
                        }
                        className={cn(
                          'flex h-11 min-w-14 flex-col items-center justify-center rounded-control border px-2 leading-tight',
                          'transition-[background-color,border-color,color] duration-100 ease-out',
                          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                          on
                            ? 'border-accent bg-selected text-accent'
                            : busy
                              ? 'border-dashed border-control bg-sunken text-secondary hover-fine:border-strong'
                              : 'border-control bg-surface text-primary hover-fine:border-strong',
                        )}
                      >
                        <span className="font-condensed text-heading font-bold figures">{pair}</span>
                        {busy ? (
                          <span aria-hidden="true" className="max-w-16 truncate text-meta font-normal text-muted">
                            {busy}
                          </span>
                        ) : slot ? (
                          <span className="text-meta font-normal text-muted figures">{slot.startsAt}</span>
                        ) : null}
                      </button>
                    );
                  })}
                </div>
                {busyPairs.size > 0 ? (
                  <p className="text-caption text-secondary">Пунктиром — пари, які в групи цього дня вже є.</p>
                ) : null}
                {errors.pairs ? <FieldError text={errors.pairs} /> : null}
              </fieldset>

              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Дисципліна" required error={errors.disciplineId}>
                  <Combobox
                    value={state.disciplineId}
                    onValueChange={(disciplineId) => patch({ disciplineId, teacherId: null, subgroup: null })}
                    options={disciplineOptions}
                    loading={disciplines.isPending}
                    placeholder="Оберіть дисципліну"
                    searchPlaceholder="Назва чи скорочення"
                  />
                </Field>
                <Field
                  label="Викладач"
                  required
                  error={errors.teacherId}
                  hint={suggested && state.teacherId === null ? 'За навантаженням групи' : declinedHint}
                >
                  <Combobox
                    value={values.teacherId}
                    onValueChange={(teacherId) => patch({ teacherId })}
                    options={teacherOptions}
                    loading={teachers.isPending}
                    placeholder="Оберіть викладача"
                    searchPlaceholder="Прізвище"
                  />
                </Field>
              </div>

              {assigned.some((t) => t.subgroup > 0) ? (
                <Field label="Для кого">
                  <Select<string>
                    value={String(values.subgroup)}
                    onValueChange={(value) => {
                      const subgroup = Number(value);
                      patch({
                        subgroup,
                        teacherId: assigned.find((t) => t.subgroup === subgroup)?.teacher.id ?? state.teacherId,
                      });
                    }}
                    options={[
                      { value: '0', label: 'Уся група' },
                      ...assigned
                        .filter((t) => t.subgroup > 0)
                        .map((t) => ({
                          value: String(t.subgroup),
                          label: `${t.subgroup} підгрупа — ${name(t.teacher)}`,
                        })),
                    ]}
                  />
                </Field>
              ) : null}

              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Аудиторія" hint="Можна не вказувати">
                  <Combobox
                    value={state.roomId}
                    onValueChange={(roomId) => patch({ roomId })}
                    options={roomOptions}
                    loading={rooms.isPending}
                    clearable
                    placeholder="Без аудиторії"
                    searchPlaceholder="Номер аудиторії"
                  />
                </Field>
                <Field label="Примітка" hint="Видно в історії зміни, наприклад «Відпрацювання»">
                  <Input
                    value={state.reasonText}
                    maxLength={100}
                    onChange={(event) => patch({ reasonText: event.target.value })}
                    placeholder="Додаткова пара"
                  />
                </Field>
              </div>
            </>
          ) : (
            <>
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1.5 text-ui font-semibold text-primary">Пара групи</legend>
                <LessonPicker
                  lessons={lessons}
                  mode={state.mode}
                  selectedId={lesson?.id ?? null}
                  groupChosen={Boolean(state.groupId)}
                  loading={schedule.isPending && Boolean(state.groupId)}
                  failed={schedule.isError}
                  onSelect={(id) =>
                    patch({
                      lessonId: id,
                      teacherId: null,
                      disciplineId: null,
                      roomId: null,
                    })
                  }
                  teacherName={name}
                />
                {errors.lesson ? <FieldError text={errors.lesson} /> : null}
              </fieldset>

              {state.mode === 'room' ? (
                <Field label="Нова аудиторія" required error={errors.roomId}>
                  <Combobox
                    value={state.roomId}
                    onValueChange={(roomId) => patch({ roomId })}
                    options={roomOptions}
                    loading={rooms.isPending}
                    disabled={!lesson}
                    placeholder={lesson ? 'Оберіть аудиторію' : 'Спершу оберіть пару'}
                    searchPlaceholder="Номер аудиторії"
                  />
                </Field>
              ) : null}

              {state.mode === 'teacher' ? (
                <>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field
                      label="Хто проведе"
                      required
                      error={errors.teacherId}
                      hint={declinedHint ?? (lesson ? 'Обрати можна лише вільних на цій парі' : undefined)}
                    >
                      <Combobox
                        value={state.teacherId}
                        onValueChange={(teacherId) => patch({ teacherId, disciplineId: null })}
                        options={freeTeacherOptions}
                        loading={availability.isPending && Boolean(lesson)}
                        disabled={!lesson}
                        placeholder={lesson ? 'Оберіть викладача' : 'Спершу оберіть пару'}
                        searchPlaceholder="Прізвище"
                      />
                    </Field>
                    <Field label="Дисципліна">
                      <Select<string>
                        value={values.disciplineId ?? ''}
                        disabled={!lesson || !state.teacherId}
                        onValueChange={(disciplineId) => patch({ disciplineId })}
                        options={[
                          ...own
                            .filter((d) => d.id !== lesson?.discipline.id)
                            .map((d) => ({
                              value: d.id,
                              label: `${d.shortName} — своя дисципліна`,
                            })),
                          ...(lesson
                            ? [
                                {
                                  value: lesson.discipline.id,
                                  label: `${lesson.discipline.shortName} — та сама`,
                                },
                              ]
                            : []),
                        ]}
                      />
                    </Field>
                  </div>
                  <ReasonPicker
                    code={reasonCode}
                    text={state.reasonText}
                    onCodeChange={(code) => patch({ reasonCode: code })}
                    onTextChange={(reasonText) => patch({ reasonText })}
                  />
                  {errors.reasonText ? <FieldError text={errors.reasonText} /> : null}
                </>
              ) : null}
            </>
          )}

          {formError ? (
            <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
              <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              {formError}
            </p>
          ) : null}
        </form>
      </DialogContent>
    </Dialog>
  );
}

function FieldError({ text }: { text: string }) {
  return (
    <p className="flex items-center gap-1.5 text-caption font-medium text-danger">
      <TriangleExclamationPoint aria-hidden="true" className="size-3.5" />
      {text}
    </p>
  );
}

interface LessonPickerProps {
  lessons: readonly LessonView[];
  mode: NewChangeMode;
  selectedId: Uuid | null;
  groupChosen: boolean;
  loading: boolean;
  failed: boolean;
  onSelect: (id: Uuid) => void;
  teacherName: (teacher: NonNullable<LessonView['teacher']>) => string;
}

/** Пари групи цього дня: обрати можна ті, що йдуть за розкладом; змінені — з поясненням, чому ні. */
function LessonPicker({ lessons, mode, selectedId, groupChosen, loading, failed, onSelect, teacherName }: LessonPickerProps) {
  if (!groupChosen) return <p className="text-caption text-muted">Оберіть групу й дату — тут зʼявляться її пари.</p>;
  if (loading) return <p className="text-caption text-muted">Дивимося розклад групи…</p>;
  if (failed) return <p className="text-caption text-danger">Розклад групи не завантажився. Спробуйте іншу дату чи оновіть сторінку.</p>;
  if (lessons.length === 0) return <p className="text-caption text-secondary">Цього дня в групи пар немає.</p>;
  return (
    <div role="radiogroup" aria-label="Пари групи" className="flex flex-col gap-1">
      {lessons.map((lesson) => {
        const selectable = lessonSelectable(lesson, mode);
        const selected = lesson.id === selectedId;
        return (
          <button
            key={lesson.id}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={!selectable}
            onClick={() => onSelect(lesson.id)}
            className={cn(
              'grid grid-cols-[2.5rem_minmax(0,1fr)] items-baseline gap-x-2 rounded-control border px-3 py-2 text-start',
              'transition-[background-color,border-color] duration-100 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
              selected ? 'border-accent bg-selected' : 'border-default bg-surface hover-fine:enabled:bg-hover',
              !selectable && 'cursor-not-allowed opacity-60',
            )}
          >
            <span className="font-condensed text-heading font-bold text-primary figures">{lesson.pair}</span>
            <span className="flex min-w-0 flex-col">
              <span className="truncate text-ui font-[650] text-primary">
                {lesson.discipline.shortName}
                {lesson.subgroup > 0 ? <span className="font-normal text-secondary"> · {lesson.subgroup} п/г</span> : null}
              </span>
              <span className="truncate text-caption text-secondary">
                {[lesson.teacher ? teacherName(lesson.teacher) : null, roomLabel(lesson.room?.name), lesson.startsAt].filter(Boolean).join(' · ')}
                {selectable ? null : ' · уже змінено'}
              </span>
            </span>
          </button>
        );
      })}
    </div>
  );
}
