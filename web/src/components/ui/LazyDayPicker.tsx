import { useEffect, useState } from 'react';
import type { DayPickerProps } from 'react-day-picker';
import { Button } from './Button';
import { loadDayPicker, loadedDayPicker, type DayPickerComponent } from './day-picker-loader';
import { Spinner } from './Spinner';

/**
 * Календар react-day-picker (українською), що вантажиться лише тоді, коли його відкривають: бібліотека
 * з date-fns і локаллю важить більше за решту сторінки розкладу, а календар відкривають рідко.
 * Поки код вантажиться — кільце на місці календаря; не завантажився — пояснення й «Спробувати ще раз».
 */
export function LazyDayPicker(props: DayPickerProps) {
  const [Calendar, setCalendar] = useState<DayPickerComponent | null>(loadedDayPicker);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (Calendar) return;
    let active = true;
    loadDayPicker().then(
      (calendar) => {
        if (active) setCalendar(() => calendar);
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
