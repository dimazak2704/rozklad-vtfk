import { useEffect, useState, type ComponentType } from 'react';
import type { DayPickerProps } from 'react-day-picker';
import { Button } from './Button';
import { Spinner } from './Spinner';

type DayPickerModule = typeof import('./DayPickerUk');

let pending: Promise<DayPickerModule> | null = null;
/** Завантажений календар: наступні відкриття малюють його одразу, без силуету. */
let loaded: ComponentType<DayPickerProps> | null = null;

/** Один запит на всі календарі сторінки; невдалий (немає мережі) не запамʼятовується — можна спробувати ще. */
function loadDayPicker(): Promise<DayPickerModule> {
  pending ??= import('./DayPickerUk').then(
    (module) => {
      loaded = module.default;
      return module;
    },
    (error: unknown) => {
      pending = null;
      throw error;
    },
  );
  return pending;
}

/**
 * Починає вантажити код календаря заздалегідь — коли людина наводить курсор чи фокус на кнопку, що його
 * відкриває: поки спливне вікно відкривається, календар уже готовий.
 */
export function preloadDayPicker(): void {
  loadDayPicker().catch(() => undefined);
}

/**
 * Календар react-day-picker (українською), що вантажиться лише тоді, коли його відкривають: бібліотека
 * з date-fns і локаллю важить більше за решту сторінки розкладу, а календар відкривають рідко.
 * Поки код вантажиться — кільце на місці календаря; не завантажився — пояснення й «Спробувати ще раз».
 */
export function LazyDayPicker(props: DayPickerProps) {
  const [Calendar, setCalendar] = useState<ComponentType<DayPickerProps> | null>(() => loaded);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (Calendar) return;
    let active = true;
    loadDayPicker().then(
      (module) => {
        if (active) setCalendar(() => module.default);
      },
      () => {
        if (active) setFailed(true);
      },
    );
    return () => {
      active = false;
    };
  }, [Calendar, attempt]);

  if (Calendar) return <Calendar {...props} />;
  // місце — як у календаря на місяць (шість тижнів), щоб спливне вікно не стрибало, коли він зʼявиться
  return (
    <div className="flex h-[19.5rem] w-[16.25rem] flex-col items-center justify-center gap-3 p-4 text-center">
      {failed ? (
        <>
          <p className="text-ui text-secondary">Календар не завантажився. Перевірте зʼєднання.</p>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              setFailed(false);
              setAttempt((value) => value + 1);
            }}
          >
            Спробувати ще раз
          </Button>
        </>
      ) : (
        <Spinner label="Завантажуємо календар" className="text-muted" />
      )}
    </div>
  );
}
