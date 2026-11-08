import { zodResolver } from '@hookform/resolvers/zod';
import { CircleCheck, TriangleExclamationPoint } from 'lucide-react';
import { useMemo } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { isApiError } from '@/api/errors';
import type { PlanningCatalog } from '@/api/timetable';
import type { LessonKind, Parity, ShiftWindowDto, TemplateLessonDto, TemplateLessonRequest } from '@/api/types';
import {
  Button,
  Combobox,
  Dialog,
  DialogClose,
  DialogContent,
  Field,
  SegmentedControl,
  Select,
  Switch,
  type ComboboxOption,
} from '@/components/ui';
import { capitalize, LESSON_KIND_LABEL, ROOM_TYPE_LABEL, weekdayShort } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import { WEEKDAYS } from '../model/grid';
import { LESSON_FORM_FIELDS, lessonSchema, requestOf, type LessonFormValues } from '../model/lessonForm';
import { SlotIndex, type Busy } from '../model/occupancy';

const LESSON_KINDS: readonly LessonKind[] = ['LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED'];

interface LessonDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Заняття, яке редагуємо; без нього — нове. */
  lesson: TemplateLessonDto | null;
  initial: LessonFormValues;
  catalog: PlanningCatalog;
  lessons: readonly TemplateLessonDto[];
  windows: readonly ShiftWindowDto[];
  onSubmit: (body: TemplateLessonRequest) => Promise<unknown>;
}

/**
 * Додавання й зміна пари. Поки заповнюєте, діалог перевіряє слот за поточною чернеткою: хто ще займає
 * групу, викладача чи аудиторію. Остаточно перевіряє сервер; зберегти з конфліктом можна — він лишиться
 * в списку конфліктів і не дасть опублікувати версію.
 */
export function LessonDialog({ open, onOpenChange, lesson, initial, catalog, lessons, windows, onSubmit }: LessonDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? (
        <LessonForm
          key={lesson?.id ?? 'new'}
          lesson={lesson}
          initial={initial}
          catalog={catalog}
          lessons={lessons}
          windows={windows}
          onSubmit={onSubmit}
          onDone={() => onOpenChange(false)}
        />
      ) : null}
    </Dialog>
  );
}

function LessonForm({
  lesson,
  initial,
  catalog,
  lessons,
  windows,
  onSubmit,
  onDone,
}: Omit<LessonDialogProps, 'open' | 'onOpenChange'> & { onDone: () => void }) {
  const form = useForm<LessonFormValues>({ resolver: zodResolver(lessonSchema), defaultValues: initial });
  const values = useWatch({ control: form.control });
  const index = useMemo(() => new SlotIndex(lessons), [lessons]);
  const groupsById = useMemo(() => new Map(catalog.groups.map((g) => [g.id, g])), [catalog.groups]);
  const roomsById = useMemo(() => new Map(catalog.rooms.map((r) => [r.id, r])), [catalog.rooms]);
  const group = values.groupId ? groupsById.get(values.groupId) : undefined;
  const ignoreIds = useMemo(() => new Set(lesson ? [lesson.id] : []), [lesson]);

  const slotQuery = {
    weekday: values.weekday ?? 1,
    pair: values.pair ?? 1,
    parity: (values.parity ?? 'BOTH'),
    groupId: values.groupId ?? '',
    subgroup: values.subgroup ?? 0,
    shift: group?.shift ?? null,
    ignoreIds,
    streamId: lesson?.streamId ?? null,
  };
  const issues: Busy[] = values.groupId
    ? index.check(
        {
          ...slotQuery,
          teacherId: values.teacherId ?? null,
          roomId: values.roomId ?? null,
          roomType: values.roomId ? (roomsById.get(values.roomId)?.type ?? null) : null,
        },
        windows,
      )
    : [];

  const groupOptions: ComboboxOption[] = useMemo(
    () =>
      catalog.groups.map((g) => ({
        value: g.id,
        label: g.code,
        description: `${g.course} курс · ${g.department}`,
        keywords: [g.specialty.name, g.department],
      })),
    [catalog.groups],
  );
  const disciplineOptions: ComboboxOption[] = useMemo(
    () =>
      catalog.disciplines.map((d) => ({
        value: d.id,
        label: d.shortName,
        description: d.name !== d.shortName ? d.name : undefined,
        keywords: [d.name],
      })),
    [catalog.disciplines],
  );
  // зайнятість викладачів і аудиторій у вибраному слоті — одразу в списку вибору
  const teacherOptions: ComboboxOption[] = catalog.teachers.map((t) => {
    const busy = values.groupId ? index.teacherLessons({ ...slotQuery, teacherId: t.id }) : [];
    return {
      value: t.id,
      label: t.shortName,
      description: busy.length > 0 ? `Зайнятий: ${busy.map((l) => `${l.group.code} · ${l.discipline.shortName}`).join(', ')}` : 'Вільний у цей час',
      keywords: [t.displayName, t.cycleCommission ?? ''],
    };
  });
  const roomOptions: ComboboxOption[] = catalog.rooms.map((r) => {
    const busy = values.groupId ? index.roomLessons({ ...slotQuery, roomId: r.id }) : [];
    const shared = r.type === 'GYM' || r.type === 'SPORTS_GROUND';
    return {
      value: r.id,
      label: roomLabel(r.name) ?? r.name,
      description:
        busy.length > 0
          ? `${shared ? 'Уже тут' : 'Зайнята'}: ${busy.map((l) => l.group.code).join(', ')}`
          : `Вільна · ${ROOM_TYPE_LABEL[r.type]}`,
      keywords: [r.name, r.building ?? ''],
    };
  });

  const submit = form.handleSubmit(async (data) => {
    try {
      await onSubmit(requestOf(data, lesson));
      onDone();
    } catch (error) {
      // помилки полів від сервера — біля полів, введене лишається; загальну причину показав тост
      if (isApiError(error)) {
        for (const [field, message] of Object.entries(error.fieldErrors())) {
          if (LESSON_FORM_FIELDS.has(field)) form.setError(field as keyof LessonFormValues, { message });
        }
      }
    }
  });

  const editing = lesson !== null;
  const hasIssues = issues.length > 0;
  const submitting = form.formState.isSubmitting;

  return (
    <DialogContent
      size="lg"
      // фокус — у перше поле форми, а не на хрестик закриття
      onOpenAutoFocus={(event) => {
        event.preventDefault();
        document.querySelector<HTMLElement>('#lesson-form [role="combobox"]')?.focus();
      }}
      title={editing ? 'Змінити пару' : 'Додати пару'}
      description={
        editing
          ? 'Зміни потрапляють у чернетку одразу після збереження; скасувати можна через Ctrl+Z.'
          : 'Пара зʼявиться в чернетці. Слот перевіряємо, поки ви заповнюєте поля.'
      }
      footer={
        <>
          <DialogClose asChild>
            <Button variant="ghost">Скасувати</Button>
          </DialogClose>
          <Button type="submit" form="lesson-form" variant="primary" loading={submitting}>
            {hasIssues ? 'Зберегти з конфліктом' : editing ? 'Зберегти' : 'Додати пару'}
          </Button>
        </>
      }
    >
      <form id="lesson-form" noValidate onSubmit={(event) => void submit(event)} className="grid gap-4 sm:grid-cols-2">
        <Field label="Група" required error={form.formState.errors.groupId?.message}>
          <Controller
            control={form.control}
            name="groupId"
            render={({ field }) => (
              <Combobox
                value={field.value || null}
                onValueChange={(value) => field.onChange(value ?? '')}
                options={groupOptions}
                placeholder="Оберіть групу"
                searchPlaceholder="Код групи: 4КІ1"
              />
            )}
          />
        </Field>

        <div className="flex flex-col gap-1.5">
          <span className="text-ui font-semibold text-primary">Хто вчиться</span>
          <Controller
            control={form.control}
            name="subgroup"
            render={({ field }) => (
              <SegmentedControl
                aria-label="Хто вчиться: уся група чи підгрупа"
                value={String(field.value)}
                onValueChange={(value) => field.onChange(Number(value))}
                options={[
                  { value: '0', label: 'Уся група' },
                  { value: '1', label: '1 підгрупа' },
                  { value: '2', label: '2 підгрупа' },
                ]}
              />
            )}
          />
        </div>

        <Field label="Дисципліна" required error={form.formState.errors.disciplineId?.message} className="sm:col-span-2">
          <Controller
            control={form.control}
            name="disciplineId"
            render={({ field }) => (
              <Combobox
                value={field.value || null}
                onValueChange={(value) => field.onChange(value ?? '')}
                options={disciplineOptions}
                placeholder="Оберіть дисципліну"
                searchPlaceholder="Скорочення або назва: СТП, мережі"
              />
            )}
          />
        </Field>

        <Field label="Викладач" hint="Біля прізвища — чи вільний у вибраному слоті" error={form.formState.errors.teacherId?.message}>
          <Controller
            control={form.control}
            name="teacherId"
            render={({ field }) => (
              <Combobox
                value={field.value}
                onValueChange={field.onChange}
                options={teacherOptions}
                placeholder="Не призначено"
                searchPlaceholder="Прізвище"
                clearable
              />
            )}
          />
        </Field>

        <Field label="Аудиторія" hint="Зайнятість — для вибраного слота" error={form.formState.errors.roomId?.message}>
          <Controller
            control={form.control}
            name="roomId"
            render={({ field }) => (
              <Combobox
                value={field.value}
                onValueChange={field.onChange}
                options={roomOptions}
                placeholder="Не призначено"
                searchPlaceholder="Номер: 301, с.з."
                clearable
              />
            )}
          />
        </Field>

        <div className="flex flex-col gap-1.5">
          <span className="text-ui font-semibold text-primary">День</span>
          <Controller
            control={form.control}
            name="weekday"
            render={({ field }) => (
              <SegmentedControl
                aria-label="День тижня"
                stretch
                value={String(field.value)}
                onValueChange={(value) => field.onChange(Number(value))}
                options={WEEKDAYS.map((weekday) => ({ value: String(weekday), label: weekdayShort(weekday) }))}
              />
            )}
          />
        </div>

        <Field label="Пара" error={form.formState.errors.pair?.message}>
          <Controller
            control={form.control}
            name="pair"
            render={({ field }) => (
              <Select
                value={String(field.value)}
                onValueChange={(value) => field.onChange(Number(value))}
                options={[1, 2, 3, 4, 5, 6, 7, 8].map((pair) => ({ value: String(pair), label: `${pair} пара` }))}
              />
            )}
          />
        </Field>

        <div className="flex flex-col gap-1.5 sm:col-span-2">
          <span className="text-ui font-semibold text-primary">Тижні</span>
          <Controller
            control={form.control}
            name="parity"
            render={({ field }) => (
              <SegmentedControl<Parity>
                aria-label="Тижні: щотижня, лише чисельник чи лише знаменник"
                value={field.value}
                onValueChange={field.onChange}
                options={[
                  { value: 'BOTH', label: 'Щотижня' },
                  { value: 'NUMERATOR', label: 'Лише чисельник' },
                  { value: 'DENOMINATOR', label: 'Лише знаменник' },
                ]}
              />
            )}
          />
        </div>

        <Field label="Вид заняття">
          <Controller
            control={form.control}
            name="lessonKind"
            render={({ field }) => (
              <Select<LessonKind>
                value={field.value}
                onValueChange={field.onChange}
                options={LESSON_KINDS.map((kind) => ({ value: kind, label: capitalize(LESSON_KIND_LABEL[kind]) }))}
              />
            )}
          />
        </Field>

        <div className="flex flex-col justify-end gap-2.5">
          <Controller
            control={form.control}
            name="online"
            render={({ field }) => <Switch label="Онлайн" checked={field.value} onCheckedChange={field.onChange} />}
          />
          <Controller
            control={form.control}
            name="pinned"
            render={({ field }) => (
              <Switch label="Закріпити для генератора" checked={field.value} onCheckedChange={field.onChange} />
            )}
          />
        </div>

        <div
          role="status"
          aria-live="polite"
          className="rounded-control border px-3 py-2.5 text-caption sm:col-span-2 data-[state=busy]:border-danger data-[state=busy]:bg-danger-subtle data-[state=free]:border-add data-[state=free]:bg-add-subtle data-[state=idle]:border-default data-[state=idle]:bg-sunken"
          data-state={!values.groupId ? 'idle' : hasIssues ? 'busy' : 'free'}
        >
          {!values.groupId ? (
            <p className="text-secondary">Оберіть групу — і ми перевіримо, чи вільний слот.</p>
          ) : hasIssues ? (
            <>
              <p className="flex items-center gap-1.5 font-semibold text-danger">
                <TriangleExclamationPoint aria-hidden="true" className="size-4" />
                Слот зайнятий
              </p>
              <ul className="mt-1 flex flex-col gap-0.5 ps-5.5 text-primary">
                {issues.map((issue) => (
                  <li key={issue.kind}>{issue.message}</li>
                ))}
              </ul>
            </>
          ) : (
            <p className="flex items-center gap-1.5 font-semibold text-add">
              <CircleCheck aria-hidden="true" className="size-4" />
              Слот вільний для групи, викладача й аудиторії
            </p>
          )}
        </div>
      </form>
    </DialogContent>
  );
}
