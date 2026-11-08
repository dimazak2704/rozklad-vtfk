import { FileImage, FileText, Search, Star, WifiOff, X } from 'lucide-react';
import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { orderDocumentUrl, usePublicChanges } from '@/api/public';
import type { IsoDate } from '@/api/types';
import { ChangesList } from '@/components/public/changes/ChangesList';
import {
  coursesIn,
  filterChangeGroups,
  hasMyTargets,
  myTargets,
  parseCourses,
} from '@/components/public/changes/changes-filter';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { useOnline } from '@/components/public/useOnline';
import {
  Button,
  Chip,
  DateNav,
  EmptyState,
  ErrorState,
  Input,
  LoadingRegion,
  ParityBadge,
  SegmentedControl,
  Skeleton,
  StatusDot,
  Tooltip,
  buttonClass,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import {
  compareIsoDates,
  formatInstant,
  formatInstantTime,
  formatShortWeekdayDate,
  formatWeekdayDateAfterNa,
  isIsoDate,
  nearestStudyDay,
  relativeDayLabel,
  shiftStudyDay,
  todayInKyiv,
} from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { useFavoritesStore } from '@/stores/favorites';

/** Знахідний після дієслова: «мають 1 групу». */
const GROUP_FORMS_ACC = ['групу', 'групи', 'груп'] as const;
const ROW_FORMS = ['зміна', 'зміни', 'змін'] as const;
/** Знахідний після «Показано» і родовий після «з»: «Показано 1 зміну з 2 змін». */
const ROW_FORMS_ACC = ['зміну', 'зміни', 'змін'] as const;
const ROW_FORMS_GEN = ['зміни', 'змін', 'змін'] as const;
/** Порожній стан у рамці: EmptyState ліворуч не має власних полів. */
const EMPTY_BOX = 'rounded-panel border border-default bg-surface px-5';

function ChangesSkeleton() {
  return (
    <LoadingRegion label="Завантажуємо заміни…">
      <div className="flex flex-col gap-3">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="grid grid-cols-[6rem_minmax(0,1fr)] gap-4 rounded-panel border border-default bg-surface p-3.5">
            <Skeleton className="h-9 w-20" />
            <div className="flex flex-col gap-2">
              <Skeleton className="h-4 w-2/3" />
              <Skeleton className="h-4 w-1/2" />
            </div>
          </div>
        ))}
      </div>
    </LoadingRegion>
  );
}

/** Сторінка «Заміни»: сьогодні, наступний день чи будь-яка дата; пошук групи, курси, «лише мої». */
export default function ChangesPage() {
  const [params, setParams] = useSearchParams();
  const online = useOnline();
  const today = todayInKyiv();
  const home = nearestStudyDay(today);
  const following = shiftStudyDay(home, 1);
  const rawDate = params.get('date');
  const date = nearestStudyDay(isIsoDate(rawDate) ? rawDate : home);
  const query = params.get('q') ?? '';
  const courseParam = params.get('course');
  const courses = useMemo(() => parseCourses(courseParam), [courseParam]);
  const mineOnly = params.get('mine') === '1';
  const favorites = useFavoritesStore((state) => state.favorites);
  const mine = useMemo(() => myTargets(favorites), [favorites]);
  const changes = usePublicChanges(date, { refetchInterval: date === today ? 60_000 : undefined });

  useDocumentTitle(`Заміни на ${formatShortWeekdayDate(date)}`);

  const patch = (changesToApply: Record<string, string | null>) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        for (const [key, value] of Object.entries(changesToApply)) {
          if (value === null || value === '') next.delete(key);
          else next.set(key, value);
        }
        return next;
      },
      { replace: true },
    );
  const setDate = (next: IsoDate) => patch({ date: nearestStudyDay(next) === home ? null : nearestStudyDay(next) });

  const groups = useMemo(() => changes.data?.groups ?? [], [changes.data]);
  const filtered = useMemo(
    () => filterChangeGroups(groups, { query, courses, mineOnly: mineOnly && hasMyTargets(mine) }, mine),
    [groups, query, courses, mineOnly, mine],
  );
  const availableCourses = coursesIn(groups);
  const filtering = query.trim() !== '' || courses.length > 0 || mineOnly;
  const rowCount = filtered.reduce((sum, g) => sum + g.items.length, 0);
  const order = changes.data?.order;
  const amendments = changes.data?.amendments ?? [];
  const past = compareIsoDates(date, today) < 0;

  const quickDay = (iso: IsoDate) => relativeDayLabel(iso, today) ?? formatShortWeekdayDate(iso);

  let content;
  if (changes.fetchStatus === 'paused' && !changes.data) {
    // без мережі запит дати стоїть на паузі (offlineFirst чекає мережу): пояснюємо, а не крутимо силуети
    content = (
      <EmptyState
        className={EMPTY_BOX}
        icon={<WifiOff aria-hidden="true" className="size-5" />}
        title="Заміни на цю дату не збережено на пристрої"
        description="Без інтернету відкриваються лише ті дні, які ви вже переглядали. Підключіться до мережі — заміни завантажаться самі."
        action={
          date !== home ? (
            <Button variant="secondary" onClick={() => setDate(home)}>
              {home === today ? 'Заміни на сьогодні' : `Заміни на ${quickDay(home).toLocaleLowerCase('uk')}`}
            </Button>
          ) : undefined
        }
      />
    );
  } else if (changes.isPending) {
    content = <ChangesSkeleton />;
  } else if (changes.isError) {
    content = (
      <ErrorState
        error={changes.error}
        title="Не вдалося завантажити заміни"
        onRetry={() => void changes.refetch()}
        retrying={changes.isFetching}
        className="rounded-panel border border-default bg-surface"
      />
    );
  } else if (groups.length === 0 && !order && !changes.data.studyDay) {
    // свято, канікули чи день поза семестром: «діє звичайний розклад» тут неправда
    content = (
      <EmptyState
        className={EMPTY_BOX}
        title={past ? 'Цього дня занять не було' : 'Цього дня занять немає'}
        description={
          changes.data.parity === null
            ? 'Дата поза семестром — канікули. Розклад цього дня не діє, замін немає.'
            : 'Свято або день без занять для всього коледжу. Розклад цього дня не діє, замін немає.'
        }
        action={
          date !== home ? (
            <Button variant="secondary" onClick={() => setDate(home)}>
              {home === today ? 'Заміни на сьогодні' : `Заміни на ${quickDay(home).toLocaleLowerCase('uk')}`}
            </Button>
          ) : undefined
        }
      />
    );
  } else if (groups.length === 0) {
    content = (
      <EmptyState
        className={EMPTY_BOX}
        title={order ? 'Змін для груп немає' : past ? 'Змін не було' : 'Розпорядження ще не опубліковано'}
        description={
          order
            ? `У розпорядженні №${order.number} змін для груп немає — заняття йдуть за розкладом.`
            : past
              ? 'Цього дня заняття йшли за розкладом.'
              : 'Поки що діє звичайний розклад. Щойно диспетчер опублікує розпорядження, зміни зʼявляться тут і в Telegram-боті.'
        }
        action={
          date !== home ? (
            <Button variant="secondary" onClick={() => setDate(home)}>
              {home === today ? 'Заміни на сьогодні' : `Заміни на ${quickDay(home).toLocaleLowerCase('uk')}`}
            </Button>
          ) : undefined
        }
      />
    );
  } else if (filtered.length === 0) {
    content = (
      <EmptyState
        className={EMPTY_BOX}
        title="За цим фільтром змін немає"
        description={`Загалом зміни мають ${countLabel(groups.length, GROUP_FORMS_ACC)}. Перевірте код групи або скиньте фільтри.`}
        action={
          <Button variant="secondary" onClick={() => patch({ q: null, course: null, mine: null })}>
            Скинути фільтри
          </Button>
        }
      />
    );
  } else {
    content = <ChangesList groups={filtered} date={date} myGroupIds={mine.groupIds} mainOrderNumber={order?.number ?? null} />;
  }

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
          <div className="flex min-w-0 flex-col gap-1.5">
            <h1 className="text-headline font-semibold tracking-[-0.005em] text-primary">
              Заміни <span className="text-secondary">на {formatWeekdayDateAfterNa(date)}</span>
            </h1>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-ui text-secondary">
              {changes.data ? <ParityBadge parity={changes.data.parity} size="sm" /> : null}
              {order ? (
                <span>
                  Розпорядження{order.number !== null ? <span className="font-semibold text-primary figures"> №{order.number}</span> : null}
                  {/* паперове розпорядження внесене в систему пізніше: час імпорту — не час видання */}
                  {order.imported ? (
                    <> (паперове, внесене в систему)</>
                  ) : order.publishedAt ? (
                    <>
                      , опубліковано <span className="figures">{formatInstant(order.publishedAt)}</span>
                    </>
                  ) : null}
                </span>
              ) : changes.data?.studyDay ? (
                <span>Розпорядження ще немає</span>
              ) : null}
              {/* рядки доповнень надруковані в їхніх власних документах, не в основному розпорядженні */}
              {amendments.length > 0 ? (
                <span>
                  доповнення{' '}
                  {amendments.map((amendment, index) => (
                    <span key={amendment.id} className="font-semibold text-primary figures">
                      {index > 0 ? ', ' : ''}№{amendment.number}
                    </span>
                  ))}
                </span>
              ) : null}
            </div>
          </div>
          {/* файли (PNG, PDF) робить система лише для власних розпоряджень; імпортоване паперове їх не має */}
          {(order && !order.imported) || amendments.some((amendment) => !amendment.imported) ? (
            <div className="flex flex-wrap gap-2">
              {order && !order.imported ? (
                <>
                  <Tooltip content={`Розпорядження №${order.number} як зображення`}>
                    <a href={orderDocumentUrl(order.id, 'png')} target="_blank" rel="noreferrer" className={buttonClass({ variant: 'secondary' })}>
                      <FileImage aria-hidden="true" className="size-4" />
                      PNG
                    </a>
                  </Tooltip>
                  <Tooltip content={`Розпорядження №${order.number} у PDF для друку`}>
                    <a href={orderDocumentUrl(order.id, 'pdf')} target="_blank" rel="noreferrer" className={buttonClass({ variant: 'secondary' })}>
                      <FileText aria-hidden="true" className="size-4" />
                      PDF
                    </a>
                  </Tooltip>
                </>
              ) : null}
              {amendments
                .filter((amendment) => !amendment.imported)
                .map((amendment) => (
                  <Tooltip key={amendment.id} content={`Доповнення №${amendment.number} у PDF для друку`}>
                    <a
                      href={orderDocumentUrl(amendment.id, 'pdf')}
                      target="_blank"
                      rel="noreferrer"
                      className={buttonClass({ variant: 'secondary' })}
                    >
                      <FileText aria-hidden="true" className="size-4" />
                      Доповнення №{amendment.number}
                    </a>
                  </Tooltip>
                ))}
            </div>
          ) : null}
        </div>

        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <SegmentedControl
            aria-label="Швидкий вибір дня"
            value={date === home ? 'home' : date === following ? 'next' : ''}
            onValueChange={(value) => setDate(value === 'home' ? home : following)}
            options={[
              { value: 'home', label: quickDay(home) },
              { value: 'next', label: quickDay(following) },
            ]}
          />
          <DateNav value={date} onChange={setDate} today={today} hotkeys />
        </div>
      </header>

      <section aria-label="Фільтри" className="flex flex-wrap items-center gap-x-3 gap-y-2.5">
        <div className="w-full sm:w-64">
          <Input
            value={query}
            onChange={(event) => patch({ q: event.target.value })}
            placeholder="Група, напр. 4КІ1"
            aria-label="Знайти групу"
            iconStart={<Search aria-hidden="true" className="size-4" />}
            endSlot={
              query ? (
                <button
                  type="button"
                  onClick={() => patch({ q: null })}
                  aria-label="Очистити пошук групи"
                  className="inline-flex size-7 items-center justify-center rounded-control text-muted hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus"
                >
                  <X aria-hidden="true" className="size-4" />
                </button>
              ) : undefined
            }
          />
        </div>
        {availableCourses.length > 1 ? (
          <div role="group" aria-label="Курси" className="flex flex-wrap gap-1.5">
            {availableCourses.map((course) => {
              const selected = courses.includes(course);
              return (
                <Chip
                  key={course}
                  selected={selected}
                  onToggle={() => {
                    const next = selected ? courses.filter((c) => c !== course) : [...courses, course].sort((a, b) => a - b);
                    patch({ course: next.length > 0 ? next.join(',') : null });
                  }}
                >
                  {course} курс
                </Chip>
              );
            })}
          </div>
        ) : null}
        {hasMyTargets(mine) ? (
          <Chip
            selected={mineOnly}
            onToggle={() => patch({ mine: mineOnly ? null : '1' })}
            icon={<Star aria-hidden="true" className={cn('size-3.5', mineOnly && 'fill-current')} />}
          >
            Лише мої
          </Chip>
        ) : (
          <Tooltip content="Додайте групу чи викладача в обране — зірочка на сторінці їхнього розкладу">
            <span tabIndex={0} className="rounded-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus">
              <Chip disabled icon={<Star aria-hidden="true" className="size-3.5" />}>
                Лише мої
              </Chip>
            </span>
          </Tooltip>
        )}
        {groups.length > 0 ? (
          <p className="ms-auto flex items-center gap-2 text-caption text-muted" aria-live="polite">
            {date === today && online ? <StatusDot tone="live" pulse label="Оновлюється наживо" /> : null}
            {filtering
              ? `Показано ${countLabel(rowCount, ROW_FORMS_ACC)} з ${countLabel(groups.reduce((s, g) => s + g.items.length, 0), ROW_FORMS_GEN)}`
              : `${countLabel(rowCount, ROW_FORMS)} у ${countLabel(groups.length, ['групі', 'групах', 'групах'])}`}
            {changes.dataUpdatedAt > 0 ? ` · о ${formatInstantTime(new Date(changes.dataUpdatedAt).toISOString())}` : ''}
          </p>
        ) : null}
      </section>

      <div aria-busy={changes.isFetching && !changes.isPending ? true : undefined}>{content}</div>
    </div>
  );
}
