import { CornerDownLeft, DoorOpen, Search, UserRound, Users, X, type LucideIcon } from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { usePublicSearch } from '@/api/meta';
import { usePublicGroups, usePublicRooms, usePublicTeachers } from '@/api/public';
import type { EntityType } from '@/api/types';
import { IconButton, Plate, Spinner, Tooltip } from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { handleSearchEscape } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import { countLabel, openScheduleHint } from '@/lib/labels';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import { findItems, fromSearchResult, groupItems, roomItems, teacherItems, type FinderItem } from './finder-items';

const TYPE_ICON: Record<EntityType, LucideIcon> = { GROUP: Users, TEACHER: UserRound, ROOM: DoorOpen };
const TYPE_LABEL: Record<EntityType, string> = { GROUP: 'група', TEACHER: 'викладач', ROOM: 'аудиторія' };

/**
 * «4КІ1» чи «Кренцін» — тихе текстове посилання в рядку «Нещодавно: …». Сам текст малий (1ВЕ — 25 px завширшки),
 * тож ціль більша за нього: невидимий псевдоелемент додає по 4 px з боків і зверху-знизу (не менше 32 px заввишки,
 * сусідні посилання через кому не перекриваються), на дотику — 44 px заввишки. Вигляд рядка не змінюється.
 * Короткий код («1ВЕ») не каже, чий це розклад, тож підказка при наведенні називає його: «Розклад групи».
 */
function TargetLink({ target }: { target: ScheduleTarget }) {
  return (
    <Tooltip content={openScheduleHint(target.type)} side="top" align="start">
      <Link
        to={target.url}
        data-target-link=""
        className={cn(
          'relative inline-flex min-h-6 items-center rounded-tag font-[650] text-accent pointer-coarse:min-h-11',
          "before:absolute before:-inset-x-1 before:-inset-y-1 before:content-['']",
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          target.type !== 'TEACHER' && 'figures',
        )}
      >
        <span className="u-line">{target.label}</span>
      </Link>
    </Tooltip>
  );
}

/** «Обране: 4КІ1, Кренцін» — підпис і посилання через кому. */
function TargetLine({ label, targets }: { label: string; targets: readonly ScheduleTarget[] }) {
  return (
    <p className="flex flex-wrap items-center gap-x-1 text-caption text-secondary">
      <span>{label}:</span>
      {targets.map((target, index) => (
        <span key={`${target.type}-${target.id}`} className="inline-flex items-center">
          <TargetLink target={target} />
          {index < targets.length - 1 ? <span aria-hidden="true">,</span> : null}
        </span>
      ))}
    </p>
  );
}

/**
 * Головний пошук: група, викладач чи аудиторія миттєво, з клавіатурою (↑/↓, Enter, Esc) і стійкістю до
 * латиниці, одруківок та англійської розкладки. Поки поле порожнє — обране й нещодавні.
 */
export function ScheduleFinder({ autoFocus = false }: { autoFocus?: boolean }) {
  const navigate = useNavigate();
  const groups = usePublicGroups();
  const teachers = usePublicTeachers();
  const rooms = usePublicRooms();
  const favorites = useFavoritesStore((state) => state.favorites);
  const recent = useFavoritesStore((state) => state.recent);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const id = useId();
  const listId = `${id}-results`;
  const hintId = `${id}-hint`;

  const catalogFailed = groups.isError || teachers.isError || rooms.isError;
  const catalogLoading = !catalogFailed && (groups.isPending || teachers.isPending || rooms.isPending);
  const items = useMemo(
    () => [...groupItems(groups.data ?? []), ...teacherItems(teachers.data ?? []), ...roomItems(rooms.data ?? [])],
    [groups.data, teachers.data, rooms.data],
  );
  // запасний шлях: довідники не завантажились — шукаємо на сервері
  const debounced = useDebouncedValue(query, 160);
  const server = usePublicSearch(catalogFailed ? debounced : '', 9);
  const results = useMemo(
    () => (catalogFailed ? (server.data ?? []).map(fromSearchResult) : findItems(items, query)),
    [catalogFailed, server.data, items, query],
  );
  const trimmed = query.trim();
  const open = trimmed.length > 0;
  const activeIndex = results.length === 0 ? -1 : Math.min(active, results.length - 1);
  const shortcuts = recent.filter((r) => !favorites.some((f) => f.type === r.type && f.id === r.id));

  useEffect(() => {
    // на телефоні не відкриваємо клавіатуру без потреби: фокус лише для миші й тачпада
    if (autoFocus && window.matchMedia('(pointer: fine)').matches) input.current?.focus({ preventScroll: true });
  }, [autoFocus]);

  const go = (item: FinderItem) => void navigate(item.url);

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (results.length === 0) return;
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setActive((current) => (Math.max(0, Math.min(current, results.length - 1)) + step + results.length) % results.length);
    } else if (event.key === 'Enter') {
      const item = results[activeIndex];
      if (item) {
        event.preventDefault();
        go(item);
      }
    } else {
      // перший Esc очищає текст, Esc у порожньому полі виводить з нього фокус — і знову діє «/» та інші скорочення
      handleSearchEscape(event, query, () => setQuery(''));
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="relative">
        <Search aria-hidden="true" className="pointer-events-none absolute start-4 top-1/2 size-5 -translate-y-1/2 text-muted" />
        <input
          ref={input}
          type="search"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
          aria-describedby={hintId}
          aria-label="Знайти розклад: група, викладач або аудиторія"
          autoComplete="off"
          spellCheck={false}
          enterKeyHint="go"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setActive(0);
          }}
          onKeyDown={onKeyDown}
          placeholder="Група, викладач, аудиторія"
          className={cn(
            'h-13 w-full rounded-popover border border-control bg-surface ps-12 text-body text-primary',
            // справа місце лише під «Очистити»: без нього підказка не обрізається на вузькому телефоні
            query ? 'pe-12' : 'pe-4',
            'transition-[border-color,box-shadow] duration-100 placeholder:text-muted hover:border-strong',
            // поле в фокусі одразу (автофокус) — тож не «подвійне кільце», а рамка 2 px акцентом і курсор
            'focus:border-accent focus:shadow-[inset_0_0_0_1px_var(--border-color-accent)] focus:outline-none',
            '[&::-webkit-search-cancel-button]:hidden',
          )}
        />
        {query ? (
          <IconButton
            label="Очистити пошук"
            size="sm"
            icon={<X aria-hidden="true" className="size-4" />}
            onClick={() => {
              setQuery('');
              input.current?.focus();
            }}
            className="absolute end-2.5 top-1/2 -translate-y-1/2"
          />
        ) : null}
      </div>
      <p id={hintId} className="sr-only">
        Код групи можна писати латиницею (4ki1), викладача — за початком прізвища, аудиторію — за номером.
      </p>

      <ul
        id={listId}
        role="listbox"
        aria-label="Результати пошуку"
        hidden={!open || results.length === 0}
        className="flex flex-col overflow-hidden rounded-popover border border-default bg-surface"
      >
        {results.map((item, index) => {
          const Icon = TYPE_ICON[item.type];
          const selected = index === activeIndex;
          return (
            <li
              key={item.key}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={selected}
              onPointerMove={() => setActive(index)}
              onClick={() => go(item)}
              className={cn(
                'flex cursor-pointer items-center gap-3 border-b border-separator px-3.5 py-2.5 last:border-b-0',
                selected ? 'bg-selected' : 'hover:bg-hover',
              )}
            >
              <Icon aria-hidden="true" className={cn('size-4 shrink-0', selected ? 'text-accent' : 'text-muted')} />
              <span className="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-2.5 gap-y-0.5">
                {item.type === 'TEACHER' ? (
                  <span className="text-body font-semibold text-primary">{item.label}</span>
                ) : (
                  <Plate size="md" tone={selected ? 'accent' : 'default'}>
                    {item.label}
                  </Plate>
                )}
                {item.sublabel ? <span className="truncate text-caption text-secondary">{item.sublabel}</span> : null}
              </span>
              <span className="hidden shrink-0 text-caption text-muted sm:inline">{TYPE_LABEL[item.type]}</span>
              <CornerDownLeft aria-hidden="true" className={cn('size-4 shrink-0 text-accent', !selected && 'invisible')} />
            </li>
          );
        })}
      </ul>

      {open && results.length === 0 ? (
        <p className="flex items-center gap-2 px-1 py-1 text-ui text-secondary">
          {catalogLoading || (catalogFailed && server.isFetching) ? (
            <>
              <Spinner size="sm" label="Шукаємо" /> Шукаємо…
            </>
          ) : (
            <>
              Нічого не знайшли за «{trimmed}». Спробуйте код групи (4КІ1), прізвище або номер аудиторії.
            </>
          )}
        </p>
      ) : null}
      <p className="sr-only" aria-live="polite">
        {open && !catalogLoading ? (results.length > 0 ? `Знайдено ${countLabel(results.length, ['результат', 'результати', 'результатів'])}` : 'Нічого не знайдено') : ''}
      </p>

      {!open && (favorites.length > 0 || shortcuts.length > 0) ? (
        <div className="flex flex-col gap-1">
          {favorites.length > 0 ? <TargetLine label="Обране" targets={favorites} /> : null}
          {shortcuts.length > 0 ? <TargetLine label="Нещодавно" targets={shortcuts.slice(0, 4)} /> : null}
        </div>
      ) : null}
    </div>
  );
}
