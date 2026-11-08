import type { ReasonCode } from '@/api/types';
import { Input, SegmentedControl, Tooltip } from '@/components/ui';
import { REASON_CODE_HINT, REASON_CODE_LABEL } from '@/lib/labels';

/** Причини, які диспетчер обирає сам (FINISHED ставить лише «Вичитано», його не пропонуємо). */
const REASON_CHOICES: readonly ReasonCode[] = ['ABSENT', 'MUTUAL', 'BUSINESS_TRIP', 'OTHER'];

interface ReasonPickerProps {
  code: ReasonCode;
  text: string;
  onCodeChange: (code: ReasonCode) => void;
  onTextChange: (text: string) => void;
}

/** Причина, яку надрукують у колонці «Причина заміни». */
export function ReasonPicker({ code, text, onCodeChange, onTextChange }: ReasonPickerProps) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-caption font-semibold text-secondary">Причина в розпорядженні</span>
      <SegmentedControl<ReasonCode>
        aria-label="Причина в розпорядженні"
        size="sm"
        value={code}
        onValueChange={onCodeChange}
        options={REASON_CHOICES.map((value) => ({
          value,
          label: (
            <Tooltip content={REASON_CODE_HINT[value]}>
              <span>{REASON_CODE_LABEL[value]}</span>
            </Tooltip>
          ),
          ariaLabel: `${REASON_CODE_LABEL[value]} — ${REASON_CODE_HINT[value]}`,
        }))}
      />
      {code === 'OTHER' ? (
        <Input
          size="sm"
          aria-label="Текст причини (до 20 символів)"
          placeholder="Напр.: Конф."
          maxLength={20}
          value={text}
          onChange={(event) => onTextChange(event.target.value)}
          className="w-32"
        />
      ) : null}
    </div>
  );
}
