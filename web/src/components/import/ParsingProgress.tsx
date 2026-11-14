import { useEffect, useState } from 'react';
import type { ImportJob } from '@/api/import';
import { Spinner } from '@/components/ui';
import { nowMs } from '@/lib/clock';
import { formatElapsed, readByAi, SLOW_PARSE_SECONDS } from './detect';

/** Секунди від завантаження — за годинником застосунку: сервер ставить createdAt у часі стенда. */
function useSecondsSince(iso: string): number {
  const since = Date.parse(iso);
  const [seconds, setSeconds] = useState(() => Math.max(0, Math.floor((nowMs() - since) / 1000)));
  useEffect(() => {
    const timer = setInterval(() => setSeconds(Math.max(0, Math.floor((nowMs() - since) / 1000))), 1000);
    return () => clearInterval(timer);
  }, [since]);
  return seconds;
}

/**
 * Поки файл розбирається: що саме відбувається (ШІ читає фото чи ми читаємо таблиці), скільки це
 * зазвичай триває і скільки вже минуло. Відсотка немає — сервер його не знає, тож не вигадуємо.
 */
export function ParsingProgress({ job }: { job: Pick<ImportJob, 'kind' | 'fileName' | 'status' | 'createdAt'> }) {
  const ai = readByAi(job);
  const seconds = useSecondsSince(job.createdAt);
  const queued = job.status === 'UPLOADED';
  const slow = seconds >= (ai ? SLOW_PARSE_SECONDS.ai : SLOW_PARSE_SECONDS.plain);

  const title = queued ? 'Файл у черзі на розбір…' : ai ? 'ШІ читає документ…' : 'Розбираємо файл…';
  const detail = queued
    ? 'Зараз розбираються інші файли — цей почнеться за ними.'
    : ai
      ? 'Модель розпізнає рядки з фото чи скану — зазвичай 15–60 секунд. Потім зіставимо назви з каталогом.'
      : 'Читаємо таблиці, зіставляємо назви з каталогом — зазвичай кілька секунд.';
  const after = slow
    ? ai
      ? 'Довше, ніж зазвичай: сервіс ШІ відповідає повільно або повторює спробу. Сторінка оновиться сама.'
      : 'Довше, ніж зазвичай: файл великий або сервер зайнятий. Сторінка оновиться сама.'
    : 'Сторінка оновиться сама — можна піти й повернутися.';

  return (
    <section aria-label="Розбір файлу" className="flex items-center gap-3 rounded-panel border border-default bg-surface px-4 py-6">
      <Spinner size="lg" label={title} />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5" aria-live="polite">
        <p className="flex flex-wrap items-baseline justify-between gap-x-3 text-ui font-semibold text-primary">
          <span>{title}</span>
          {/* лічильник щосекунди не зачитуємо: у живій області лише зміни стану */}
          <span aria-hidden="true" className="text-caption font-normal text-muted figures">
            минуло {formatElapsed(seconds)}
          </span>
        </p>
        <p className="text-caption text-secondary">{detail}</p>
        <p className="text-caption text-secondary">{after}</p>
      </div>
    </section>
  );
}
