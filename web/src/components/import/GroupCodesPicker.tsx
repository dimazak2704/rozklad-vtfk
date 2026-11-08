import { X } from 'lucide-react';
import { useCatalogOptions } from '@/api/catalog';
import { Combobox, IconButton, Plate } from '@/components/ui';

interface GroupCodesPickerProps {
  value: readonly string[];
  onChange: (codes: string[]) => void;
  disabled?: boolean;
}

/**
 * Вибір груп за кодами (importer привʼязує години до кодів груп, `options.groupCodes`): пошук у каталозі
 * активних груп і список вибраних з кнопкою «прибрати».
 */
export function GroupCodesPicker({ value, onChange, disabled }: GroupCodesPickerProps) {
  const groups = useCatalogOptions('groups', { active: true });
  const options = (groups.items ?? [])
    .filter((g) => !value.includes(g.code))
    .map((g) => ({ value: g.code, label: g.code, description: `${g.course} курс` }));
  return (
    <div className="flex flex-col gap-2">
      <Combobox
        value={null}
        onValueChange={(code) => {
          if (code) onChange([...value, code]);
        }}
        options={options}
        placeholder="Додати групу…"
        searchPlaceholder="Код групи"
        loading={groups.isPending}
        disabled={disabled}
      />
      {value.length > 0 ? (
        <ul className="flex flex-wrap gap-1.5" aria-label="Вибрані групи">
          {value.map((code) => (
            <li key={code} className="inline-flex items-center gap-0.5">
              <Plate size="md">{code}</Plate>
              <IconButton
                label={`Прибрати групу ${code}`}
                icon={<X aria-hidden="true" className="size-3.5" />}
                size="sm"
                className="size-7"
                disabled={disabled}
                onClick={() => onChange(value.filter((g) => g !== code))}
              />
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
