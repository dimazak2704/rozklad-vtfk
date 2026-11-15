import { useId } from 'react';
import type { GenerationWeights } from '@/api/types';
import { Select } from '@/components/ui';
import { customWeightText, defaultPriority, PRIORITIES, PRIORITY_LABEL, priorityOf, withPriority, type Priority, type PriorityRule } from './priorities';

const CUSTOM = 'custom';

interface PrioritySelectProps {
  rule: PriorityRule;
  weights: GenerationWeights;
  onChange: (weights: GenerationWeights) => void;
  disabled?: boolean;
}

/**
 * Пріоритет одного мʼякого правила: назва зліва, рівень справа. Чесне речення про правило — для читача
 * екрана тут, для очей — у спільній розгортці «Що означають пріоритети?». Ваги, що не збігаються з жодним
 * рівнем (стара задача з повзунками), показано як «вага N», доки людина не вибере рівень.
 */
export function PrioritySelect({ rule, weights, onChange, disabled }: PrioritySelectProps) {
  const id = useId();
  const priority = priorityOf(rule, weights);
  const changed = priority !== defaultPriority(rule);
  const options = [
    ...PRIORITIES.map((value) => ({ value, label: PRIORITY_LABEL[value] })),
    ...(priority === null ? [{ value: CUSTOM, label: customWeightText(rule, weights), disabled: true }] : []),
  ];
  return (
    <div className="flex min-h-9 items-center justify-between gap-3" data-rule={rule.id}>
      <span id={`${id}-label`} className="min-w-0 text-ui text-primary">
        {rule.label}
        {changed ? <span className="text-caption text-muted"> · змінено</span> : null}
      </span>
      <Select<string>
        size="sm"
        className="w-44 shrink-0"
        aria-label={rule.label}
        aria-describedby={`${id}-hint`}
        value={priority ?? CUSTOM}
        disabled={disabled}
        onValueChange={(value) => {
          if (value !== CUSTOM) onChange(withPriority(weights, rule, value as Priority));
        }}
        options={options}
      />
      <span id={`${id}-hint`} className="sr-only">
        {rule.hint}
      </span>
    </div>
  );
}
