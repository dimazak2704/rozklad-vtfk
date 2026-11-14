import { FileImage, FileText, Info, Search, WifiOff, X } from 'lucide-react';
import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { buildUrl } from '@/api/client';
import { orderDocumentUrl, usePublicChanges } from '@/api/public';
import { savedCopyTimeLabel, useSavedCopy } from '@/api/saved-copy';
import type { IsoDate } from '@/api/types';
import { ChangesDatePicker } from '@/components/public/changes/ChangesDatePicker';
import { ChangesList } from '@/components/public/changes/ChangesList';
import { HeaderParity } from '@/components/public/HeaderParity';
import { SavedCopyNote } from '@/components/public/SavedCopyNote';
import { dominantReason, filterChangeGroups, hasMyTargets, myTargets } from '@/components/public/changes/changes-filter';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { useOnline } from '@/components/public/useOnline';
import {
  Button,
  EmptyState,
  ErrorState,
  HoverDetails,
  Input,
  LoadingRegion,
  PageErrorScope,
  PageHeader,
  SegmentedControl,
  Skeleton,
  StatusDot,
  type MoreMenuItem,
} from '@/components/ui';
import {
  compareIsoDates,
  formatInstant,
  formatInstantTime,
  formatShortWeekdayDate,
  formatWeekdayDate,
  formatWeekdayDateAfterNa,
  isIsoDate,
  nearestStudyDay,
  relativeDayLabel,
  shiftStudyDay,
  todayInKyiv,
} from '@/lib/date';
import { useHotkeys } from '@/hooks/useHotkeys';
import { toClockMs } from '@/lib/clock';
import { countLabel } from '@/lib/labels';
import { useFavoritesStore } from '@/stores/favorites';

/** Пошук групи зʼявляється, лише коли список довгий: на 3–4 групи він тільки заважає. */
const SEARCH_FROM = 6;
/** Місцевий: «у 4 групах». */
const GROUP_FORMS_LOC = ['групі', 'групах', 'групах'] as const;
/** Знахідний після дієслова: «мають 1 групу». */
const GROUP_FORMS_ACC = ['групу', 'групи', 'груп'] as const;
const ROW_FORMS = ['заміна', 'заміни', 'замін'] as const;
/** Знахідний після «Показано» і родовий після «з»: «Показано 1 заміну з 2 замін». */
const ROW_FORMS_ACC = ['заміну', 'заміни', 'замін'] as const;
const ROW_FORMS_GEN = ['заміни', 'замін', 'замін'] as const;
/** Порожній стан — речення на тлі сторінки, без рамки. */
const EMPTY_BOX = 'py-2';

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

/**
 * «Заміни»: сьогодні, наступний день чи будь-яка дата. Під заголовком — лише дата й парність; над списком —
 * один рядок підсумку («4 заміни у 4 групах»); файли розпорядження — у «⋯»; номер і час публікації —
 * у картці «Джерело» під списком, поруч зі спільною причиною.
 */
export default function ChangesPage() {
  const [params, setParams] = useSearchParams();
  const online = useOnline();
  const today = todayInKyiv();
  const home = nearestStudyDay(today);
  const following = shiftStudyDay(home, 1);
  const rawDate = params.get('date');
  const date = nearestStudyDay(isIsoDate(rawDate) ? rawDate : home);
  const query = params.get('q') ?? '';
  const mineOnly = params.get('mine') === '1';
  const favorites = useFavoritesStore((state) => state.favorites);
  const mine = useMemo(() => myTargets(favorites), [favorites]);
  const changes = usePublicChanges(date, { refetchInterval: date === today ? 60_000 : undefined });

  // сервер не відповів за 5 с чи недоступний, а Service Worker віддав збережену копію: це не «наживо»
  const savedCopy = useSavedCopy(buildUrl('/public/changes', { date }));
  const showSavedNote = Boolean(changes.data) && (!online || savedCopy.fromCache);

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
  // ←/→ — попередній і наступний навчальний день; окремих кнопок для цього на екрані немає
  useHotkeys([
    { keys: 'arrowleft', handler: () => setDate(shiftStudyDay(date, -1)), description: 'Попередній день', group: 'Заміни' },
    { keys: 'arrowright', handler: () => setDate(shiftStudyDay(date, 1)), description: 'Наступний день', group: 'Заміни' },
  ]);

  const groups = useMemo(() => changes.data?.groups ?? [], [changes.data]);
  // однакова причина в кожному рядку — шум: пишемо її один раз під списком, у рядку — лише інші
  const commonReason = useMemo(() => dominantReason(groups), [groups]);
  const filtered = useMemo(
    () => filterChangeGroups(groups, { query, courses: [], mineOnly: mineOnly && hasMyTargets(mine) }, mine),
    [groups, query, mineOnly, mine],
  );
  const filtering = query.trim() !== '' || (mineOnly && hasMyTargets(mine));
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
          <Button variant="secondary" onClick={() => patch({ q: null, mine: null })}>
            Скинути фільтри
          </Button>
        }
      />
    );
  } else {
    content = (
      <ChangesList groups={filtered} date={date} myGroupIds={mine.groupIds} mainOrderNumber={order?.number ?? null} commonReason={commonReason} />
    );
  }

  const more: MoreMenuItem[] = [];
  if (order && !order.imported) {
    more.push(
      { label: `Розпорядження №${order.number} — зображення`, icon: <FileImage aria-hidden="true" className="size-4" />, href: orderDocumentUrl(order.id, 'png') },
      { label: `Розпорядження №${order.number} — PDF`, icon: <FileText aria-hidden="true" className="size-4" />, href: orderDocumentUrl(order.id, 'pdf') },
    );
  }
  for (const amendment of amendments.filter((item) => !item.imported)) {
    more.push({ label: `Доповнення №${amendment.number} — PDF`, icon: <FileText aria-hidden="true" className="size-4" />, href: orderDocumentUrl(amendment.id, 'pdf') });
  }

  const totalRows = groups.reduce((sum, g) => sum + g.items.length, 0);
  const updated = changes.dataUpdatedAt > 0 ? formatInstantTime(new Date(toClockMs(changes.dataUpdatedAt)).toISOString()) : null;
  // один рядок підсумку над списком: скільки замін (і «наживо», коли це сьогодні); час оновлення — у «Джерелі»
  const summary =
    groups.length > 0 ? (
      <h2 className="flex flex-wrap items-center gap-x-2 text-ui font-semibold text-primary" aria-live="polite">
        {date === today && online && !savedCopy.fromCache ? <StatusDot tone="live" label="Оновлюється наживо" /> : null}
        {filtering
          ? `Показано ${countLabel(rowCount, ROW_FORMS_ACC)} з ${countLabel(totalRows, ROW_FORMS_GEN)}`
          : `${countLabel(totalRows, ROW_FORMS)} у ${countLabel(groups.length, GROUP_FORMS_LOC)}`}
      </h2>
    ) : null;

  // номери документів і час публікації потрібні тим, хто звіряє з папером: вони в картці «Джерело», не в тексті
  const sourceDetails = order ? (
    <div className="flex flex-col gap-1 text-ui text-secondary">
      <p>
        Розпорядження{order.number !== null ? <span className="figures"> №{order.number}</span> : null}
        {/* паперове розпорядження внесене в систему пізніше: час імпорту — не час видання */}
        {order.imported ? (
          <> (паперове, внесене в систему)</>
        ) : order.publishedAt ? (
          <>
            , опубліковано <span className="figures">{formatInstant(order.publishedAt)}</span>
          </>
        ) : null}
        .
      </p>
      {/* рядки доповнень надруковані в їхніх власних документах, не в основному розпорядженні */}
      {amendments.length > 0 ? (
        <p>
          <span>
            доповнення{' '}
            {amendments.map((amendment, index) => (
              <span key={amendment.id} className="figures">
                {index > 0 ? ', ' : ''}№{amendment.number}
              </span>
            ))}
          </span>
          .
        </p>
      ) : null}
      {updated ? (
        <p className="text-muted">
          Сторінка оновлена о <span className="figures">{updated}</span>.
        </p>
      ) : null}
    </div>
  ) : null;

  const source = order ? (
    <p className="flex flex-wrap items-center gap-x-2 text-caption text-muted">
      {commonReason ? <span>Причина — {commonReason.toLocaleLowerCase('uk')}, якщо біля рядка не сказано інше.</span> : null}
      <HoverDetails content={sourceDetails} label="Джерело замін" side="top">
        <button
          type="button"
          className="-mx-1 inline-flex min-h-7 items-center gap-1 rounded-control px-1 font-semibold text-secondary focus-visible:outline-2 focus-visible:outline-focus pointer-coarse:min-h-11"
        >
          <Info aria-hidden="true" className="size-3.5" />
          <span className="u-line">Джерело</span>
        </button>
      </HoverDetails>
    </p>
  ) : null;

  const showSearch = groups.length >= SEARCH_FROM || query.trim() !== '';
  const showMine = hasMyTargets(mine) && groups.length > 1;

  return (
    // шапка — на всю ширину сторінки, як на /g/: перемикач днів і календар закінчуються на правому краї контейнера;
    // фільтри й список — колонкою для читання (max-w-4xl)
    <PageErrorScope queries={[changes]} title="Заміни зараз не завантажуються">
      <PageHeader
        size="headline"
        title={
          <>
            <span aria-hidden="true">Заміни</span>
            <span className="sr-only">{`Заміни на ${formatWeekdayDateAfterNa(date)}`}</span>
          </>
        }
        meta={
          <>
            <span>{formatWeekdayDate(date)}</span>
            {changes.data?.parity ? (
              <>
                <span aria-hidden="true">·</span>
                <HeaderParity parity={changes.data.parity} />
              </>
            ) : null}
          </>
        }
        controls={
          <>
            <SegmentedControl
              aria-label="Швидкий вибір дня"
              value={date === home ? 'home' : date === following ? 'next' : ''}
              onValueChange={(value) => setDate(value === 'home' ? home : following)}
              options={[
                { value: 'home', label: quickDay(home) },
                { value: 'next', label: quickDay(following) },
              ]}
            />
            <ChangesDatePicker value={date} today={today} onChange={setDate} />
          </>
        }
        more={more}
      />

      {showSearch || showMine ? (
        <section aria-label="Фільтри" className="flex max-w-4xl flex-wrap items-center gap-x-3 gap-y-2.5">
          {showSearch ? (
            <div className="w-full sm:w-72">
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
                      className="inline-flex size-7 items-center justify-center rounded-control text-muted hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus pointer-coarse:size-11"
                    >
                      <X aria-hidden="true" className="size-4" />
                    </button>
                  ) : undefined
                }
              />
            </div>
          ) : null}
          {showMine ? (
            <SegmentedControl
              aria-label="Які заміни показати"
              value={mineOnly ? 'mine' : 'all'}
              onValueChange={(value) => patch({ mine: value === 'mine' ? '1' : null })}
              options={[
                { value: 'all', label: 'Усі' },
                { value: 'mine', label: 'Лише мої' },
              ]}
            />
          ) : null}
        </section>
      ) : null}

      <div aria-busy={changes.isFetching && !changes.isPending ? true : undefined} className="flex max-w-4xl flex-col gap-3">
        {showSavedNote ? (
          <SavedCopyNote online={online} onRefresh={() => void changes.refetch()}>
            {savedCopy.fromCache && savedCopy.savedAt !== null
              ? `Показано заміни, збережені ${savedCopyTimeLabel(savedCopy.savedAt)} — могли змінитися.`
              : 'Показано збережені заміни — могли змінитися.'}
          </SavedCopyNote>
        ) : null}
        {summary}
        {content}
        {source}
      </div>
    </PageErrorScope>
  );
}
