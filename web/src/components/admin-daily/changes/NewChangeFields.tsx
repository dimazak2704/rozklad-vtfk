import { Combobox, Field, Input, Select } from '@/components/ui';
import { useTeacherLabel } from '../useTeacherLabel';
import { FieldError, LessonPicker, PairPicker } from './NewChangePickers';
import { ReasonPicker } from './ReasonPicker';
import type { NewChangeForm } from './useNewChangeForm';

/** Додаткова пара: номери пар, дисципліна й викладач (з навантаження), підгрупа, аудиторія й примітка. */
export function AdditionalFields({ form }: { form: NewChangeForm }) {
  const name = useTeacherLabel();
  const { state, patch, values, errors, options, assigned, suggested } = form;
  return (
    <>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1.5 text-ui font-semibold text-primary">
          Пара <span className="font-normal text-muted">— одна чи кілька підряд</span>
        </legend>
        <PairPicker
          pairs={form.dayPairs}
          slots={form.slots}
          selected={state.pairs}
          busy={form.busyPairs}
          onToggle={(pair) =>
            patch({
              pairs: state.pairs.includes(pair) ? state.pairs.filter((p) => p !== pair) : [...state.pairs, pair],
            })
          }
        />
        {form.busyPairs.size > 0 ? <p className="text-caption text-secondary">Пунктиром — пари, які в групи цього дня вже є.</p> : null}
        {errors.pairs ? <FieldError text={errors.pairs} /> : null}
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Дисципліна" required error={errors.disciplineId}>
          <Combobox
            value={state.disciplineId}
            onValueChange={(disciplineId) => patch({ disciplineId, teacherId: null, subgroup: null })}
            options={options.discipline}
            loading={options.disciplinesLoading}
            placeholder="Оберіть дисципліну"
            searchPlaceholder="Назва чи скорочення"
          />
        </Field>
        <Field label="Викладач" required error={errors.teacherId} hint={suggested && state.teacherId === null ? 'За навантаженням групи' : form.declinedHint}>
          <Combobox
            value={values.teacherId}
            onValueChange={(teacherId) => patch({ teacherId })}
            options={options.teacher}
            loading={form.loading.teachers}
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
            options={options.room}
            loading={options.roomsLoading}
            clearable
            placeholder="Без аудиторії"
            searchPlaceholder="Номер аудиторії"
          />
        </Field>
        <Field label="Примітка" hint="Видно в історії зміни, наприклад «Відпрацювання»">
          <Input value={state.reasonText} maxLength={100} onChange={(event) => patch({ reasonText: event.target.value })} placeholder="Додаткова пара" />
        </Field>
      </div>
    </>
  );
}

/** Зміна наявної пари групи: обрати пару, а далі — нова аудиторія чи інший викладач з причиною. */
export function LessonChangeFields({ form }: { form: NewChangeForm }) {
  const name = useTeacherLabel();
  const { state, patch, values, errors, options, lesson, own } = form;
  return (
    <>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1.5 text-ui font-semibold text-primary">Пара групи</legend>
        <LessonPicker
          lessons={form.lessons}
          mode={state.mode}
          selectedId={lesson?.id ?? null}
          groupChosen={Boolean(state.groupId)}
          loading={form.scheduleLoading}
          failed={form.scheduleFailed}
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
            options={options.room}
            loading={options.roomsLoading}
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
              hint={form.declinedHint ?? (lesson ? 'Обрати можна лише вільних на цій парі' : undefined)}
            >
              <Combobox
                value={state.teacherId}
                onValueChange={(teacherId) => patch({ teacherId, disciplineId: null })}
                options={options.freeTeacher}
                loading={form.loading.availability}
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
            code={form.reasonCode}
            text={state.reasonText}
            onCodeChange={(code) => patch({ reasonCode: code })}
            onTextChange={(reasonText) => patch({ reasonText })}
          />
          {errors.reasonText ? <FieldError text={errors.reasonText} /> : null}
        </>
      ) : null}
    </>
  );
}
