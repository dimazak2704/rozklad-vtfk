import { useState, type RefObject } from 'react';
import { Controller, type Control } from 'react-hook-form';
import { Button, Field, Textarea } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatShortWeekdayDate } from '@/lib/date';
import { countLabel, PAIR_FORMS_ACC } from '@/lib/labels';
import { COMMENT_MAX, type AbsenceFormValues } from './absence-schema';
import type { OwnLesson } from './absence-choices';

export function LessonLines({ items }: { items: readonly OwnLesson[] }) {
  return (
    <ul className="mt-1 flex flex-col gap-0.5 text-secondary">
      {items.slice(0, 6).map(({ day, lesson }) => (
        <li key={`${day.date}-${lesson.id}`} className="figures">
          {formatShortWeekdayDate(day.date)} · {lesson.pair} пара — {lesson.group.code}, {lesson.discipline.shortName}
        </li>
      ))}
      {items.length > 6 ? <li>і ще {items.length - 6}</li> : null}
    </ul>
  );
}

/** Що зачепить відсутність на весь день: його пари в обрані дні або спокійне «пар немає». */
export function AffectedPreview({ own }: { own: readonly OwnLesson[] }) {
  if (own.length === 0) return <p className="text-secondary">У ці дні за розкладом у вас пар немає.</p>;
  return (
    <>
      <p className="font-semibold text-primary">Зачепить {countLabel(own.length, PAIR_FORMS_ACC)}:</p>
      <LessonLines items={own} />
    </>
  );
}

/**
 * Пара, якої немає в розкладі (консультація, перенесена пара): номери лише на запит — «Інша пара…».
 * Відкрито одразу, коли своїх пар у ці дні немає або вже обрано номер поза списком.
 */
export function OtherPairs({
  pairs,
  selected,
  open: forced,
  onToggle,
  label = 'Інша пара',
  caption = 'Інша пара — за номером:',
}: {
  pairs: readonly number[];
  selected: readonly number[];
  open: boolean;
  onToggle: (pair: number, on: boolean) => void;
  /** Назва групи кнопок для зчитувача екрана. */
  label?: string;
  /** Підпис над кнопками; null — без підпису (його вже каже речення вище). */
  caption?: string | null;
}) {
  const [opened, setOpened] = useState(false);
  if (pairs.length === 0) return null;
  if (!forced && !opened) {
    return (
      <Button variant="ghost" className="-ms-2.5 self-start" onClick={() => setOpened(true)}>
        Інша пара…
      </Button>
    );
  }
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2 pt-2">
      {caption ? <p className="text-caption text-secondary">{caption}</p> : null}
      <div className="flex flex-wrap gap-1.5">
        {pairs.map((pair) => {
          const on = selected.includes(pair);
          return (
            <button
              key={pair}
              type="button"
              aria-pressed={on}
              aria-label={`${pair} пара`}
              onClick={() => onToggle(pair, !on)}
              className={cn(
                'inline-flex size-10 items-center justify-center rounded-control border text-cell font-[650] figures pointer-coarse:size-11',
                'transition-[background-color,border-color,color] duration-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                on ? 'border-accent bg-selected text-primary' : 'border-control bg-surface text-primary hover-fine:border-strong hover-fine:bg-hover',
              )}
            >
              {pair}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Коментар для диспетчера. Для «Інше» це те саме поле, але під варіантами причини й з питанням «Що саме?»:
 * два поля для одного й того самого тексту лише плутали б. Текст переживає зміну причини.
 */
export function CommentField({
  control,
  inputRef,
  other = false,
  error,
}: {
  control: Control<AbsenceFormValues>;
  inputRef: RefObject<HTMLTextAreaElement | null>;
  other?: boolean;
  error?: string;
}) {
  return (
    <Field
      // під «Інше» — з відступом до тексту варіантів (кружок 18 px + проміжок 12 px), щоб було видно, до чого питання
      className={other ? 'ms-[1.875rem]' : undefined}
      label={
        <>
          {other ? 'Що саме?' : 'Коментар для диспетчера'} <span className="font-[500] text-muted">(необовʼязково)</span>
        </>
      }
      error={error}
    >
      <Controller
        control={control}
        name="comment"
        render={({ field }) => (
          <Textarea
            name={field.name}
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            ref={(element) => {
              field.ref(element);
              inputRef.current = element;
            }}
            rows={2}
            maxLength={COMMENT_MAX}
            placeholder={other ? 'Наприклад, курси підвищення кваліфікації' : undefined}
          />
        )}
      />
    </Field>
  );
}
