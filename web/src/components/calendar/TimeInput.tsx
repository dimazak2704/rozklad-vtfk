import { useState } from 'react';
import { Input } from '@/components/ui';
import { cn } from '@/lib/cn';
import { normalizeTime } from './bells';

interface TimeInputProps {
  value: string;
  onChange: (value: string) => void;
  'aria-label': string;
  'aria-invalid'?: boolean;
  className?: string;
}

/**
 * Час пари текстом «08:30» — завжди 24 години, незалежно від мови браузера (нативне `type="time"`
 * в англійській локалі показує «08:30 AM»). Цифрова клавіатура на телефоні; «830» → «08:30» після виходу з поля.
 */
export function TimeInput({ value, onChange, className, ...aria }: TimeInputProps) {
  const [draft, setDraft] = useState<string | null>(null);
  const shown = draft ?? value;
  return (
    <Input
      type="text"
      inputMode="numeric"
      autoComplete="off"
      size="sm"
      maxLength={5}
      placeholder="00:00"
      value={shown}
      aria-label={aria['aria-label']}
      aria-invalid={aria['aria-invalid']}
      onChange={(event) => {
        const next = event.target.value.replace(/[^\d:.,]/g, '');
        setDraft(next);
        // повний правильний час одразу оновлює тривалість і перерви
        if (/^\d{2}:\d{2}$/.test(next) && normalizeTime(next)) onChange(next);
      }}
      onBlur={() => {
        if (draft === null) return;
        onChange(normalizeTime(draft) ?? draft);
        setDraft(null);
      }}
      className={cn('figures text-center', className)}
    />
  );
}
