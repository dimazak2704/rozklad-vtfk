import { Check } from 'lucide-react';
import { useId, useState } from 'react';
import type { LessonView } from '@/api/types';
import { LessonCell } from '@/components/schedule';
import { Input } from '@/components/ui';
import { cn } from '@/lib/cn';
import { HEX_COLOR_RE, LINE_SWATCHES, lineName, lineOf, swatchOf } from './discipline-palette';

interface DisciplineColorFieldProps {
  value: string | null;
  onChange: (value: string | null) => void;
  /** id дисципліни; для нової — null (колір «авто» з’явиться після збереження). */
  disciplineId: string | null;
  shortName: string;
  name: string;
  error?: string;
}

const AUTO = 'auto';

/**
 * Колір лінії дисципліни: «авто» (з id), одна з 12 перевірених ліній або свій hex — його система
 * зведе до найближчої лінії, щоб текст лишався читабельним. Праворуч — заняття так, як його побачать
 * у розкладі, у світлій і темній темах.
 */
export function DisciplineColorField({ value, onChange, disciplineId, shortName, name, error }: DisciplineColorFieldProps) {
  const groupName = useId();
  const swatch = swatchOf(value);
  const [custom, setCustom] = useState(value && !swatch ? value : '');
  const selected = value === null ? AUTO : swatch ? String(swatch.line) : 'custom';
  const previewId = disciplineId ?? 'new-discipline';
  const line = lineOf(previewId, value);
  const customValid = HEX_COLOR_RE.test(custom);

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_17rem]">
      <fieldset className="flex min-w-0 flex-col gap-3">
        <legend className="mb-1.5 text-ui font-semibold text-primary">Колір лінії</legend>
        <div className="flex flex-wrap items-center gap-1.5" role="presentation">
          <label
            className={cn(
              'inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-full border px-3 text-caption font-semibold select-none',
              'has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-focus',
              selected === AUTO ? 'border-accent bg-selected text-accent' : 'border-default text-secondary hover:bg-hover hover:text-primary',
            )}
          >
            <input
              type="radio"
              name={groupName}
              value={AUTO}
              checked={selected === AUTO}
              onChange={() => onChange(null)}
              className="sr-only"
            />
            Авто
          </label>
          {LINE_SWATCHES.map((item) => {
            const checked = selected === String(item.line);
            return (
              <label
                key={item.line}
                data-line={item.line}
                title={`Лінія: ${item.name}`}
                className={cn(
                  'relative grid size-8 cursor-pointer place-items-center rounded-full border-2 transition-[border-color] duration-100',
                  'has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-focus',
                  checked ? 'border-[var(--line-dot)]' : 'border-transparent hover:border-strong',
                )}
              >
                <input
                  type="radio"
                  name={groupName}
                  value={item.line}
                  checked={checked}
                  onChange={() => onChange(item.hex)}
                  aria-label={`Лінія: ${item.name}`}
                  className="sr-only"
                />
                <span aria-hidden="true" className="grid size-5 place-items-center rounded-full bg-[var(--line-dot)] text-on-accent">
                  {checked ? <Check className="size-3 stroke-[3]" /> : null}
                </span>
              </label>
            );
          })}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor={`${groupName}-custom`} className="text-caption text-secondary">
            Свій колір
          </label>
          <Input
            id={`${groupName}-custom`}
            size="sm"
            value={custom}
            placeholder="#1f6feb"
            maxLength={7}
            spellCheck={false}
            autoComplete="off"
            aria-invalid={Boolean(error) || (custom !== '' && !customValid) || undefined}
            onChange={(event) => {
              const next = event.target.value.trim();
              setCustom(next);
              if (HEX_COLOR_RE.test(next)) onChange(next.toLowerCase());
            }}
            className="w-28 font-mono"
          />
          {selected === 'custom' && value ? (
            <span className="text-caption text-muted">
              у розкладі — найближча лінія: <span className="font-semibold text-secondary">{lineName(line)}</span>
            </span>
          ) : null}
        </div>
        <p className="text-caption text-muted">
          {value === null
            ? disciplineId
              ? `Колір підібрано автоматично за дисципліною: ${lineName(line)}.`
              : 'Колір підбере система після збереження — сталий для цієї дисципліни.'
            : 'Колір бачать студенти й викладачі в усіх розкладах.'}
        </p>
        {error ? <p className="text-caption font-medium text-danger">{error}</p> : null}
      </fieldset>
      <figure className="flex flex-col gap-2">
        <figcaption className="text-caption text-muted">Так виглядатиме в розкладі</figcaption>
        <div className="grid grid-cols-2 gap-2">
          {(['light', 'dark'] as const).map((theme) => (
            <div key={theme} data-theme={theme} className="flex flex-col gap-1.5 rounded-panel border border-default bg-surface p-2">
              <span className="text-meta text-muted">{theme === 'light' ? 'Світла тема' : 'Темна тема'}</span>
              <LessonCell lesson={previewLesson(previewId, shortName || 'Назва', name, value)} perspective="teacher" />
            </div>
          ))}
        </div>
      </figure>
    </div>
  );
}

function previewLesson(id: string, shortName: string, name: string, color: string | null): LessonView {
  return {
    id: `preview-${id}`,
    pair: 2,
    startsAt: '09:40',
    endsAt: '10:40',
    discipline: { id, shortName, name: name || shortName, color: color && HEX_COLOR_RE.test(color) ? color : null },
    teacher: null,
    room: { id: 'preview-room', name: '301', type: 'CLASSROOM' },
    group: { id: 'preview-group', code: '4КІ1', course: 4 },
    subgroup: 0,
    kind: 'REGULAR',
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
  };
}
