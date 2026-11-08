import { CornerDownLeft, DoorOpen, Search, Star, UserRound, Users, X, type LucideIcon } from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { usePublicSearch } from '@/api/meta';
import { usePublicGroups, usePublicRooms, usePublicTeachers } from '@/api/public';
import type { EntityType } from '@/api/types';
import { IconButton, Plate, Spinner } from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import { findItems, fromSearchResult, groupItems, roomItems, teacherItems, type FinderItem } from './finder-items';

const TYPE_ICON: Record<EntityType, LucideIcon> = { GROUP: Users, TEACHER: UserRound, ROOM: DoorOpen };
const TYPE_LABEL: Record<EntityType, string> = { GROUP: 'група', TEACHER: 'викладач', ROOM: 'аудиторія' };

function TargetChip({ target, favorite = false }: { target: ScheduleTarget; favorite?: boolean }) {
  const Icon = TYPE_ICON[target.type];
  return (
    <Link
      to={target.url}
      className={cn(
        'inline-flex h-8 max-w-full items-center gap-1.5 rounded-full border bg-surface ps-2.5 pe-3 text-ui font-semibold',
        'transition-[border-color,color] duration-100 hover:border-strong hover:text-primary',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        favorite ? 'border-accent-subtle text-primary' : 'border-default text-secondary',
      )}
    >
      {favorite ? (
        <Star aria-hidden="true" className="size-3.5 fill-current text-accent" />
      ) : (
        <Icon aria-hidden="true" className="size-3.5 text-muted" />
      )}
      <span className={cn('truncate', target.type !== 'TEACHER' && 'font-condensed')}>{target.label}</span>
    </Link>
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
  const forgetRecent = useFavoritesStore((state) => state.forgetRecent);
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
    } else if (event.key === 'Escape' && query) {
      event.preventDefault();
      setQuery('');
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
          placeholder="Група, викладач або аудиторія"
          className={cn(
            'h-13 w-full rounded-panel border border-control bg-surface ps-12 pe-12 text-body text-primary sm:h-14 sm:text-heading',
            'transition-[border-color] duration-100 placeholder:text-muted hover:border-strong',
            'focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
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
      <p id={hintId} className="text-caption text-muted">
        Код групи можна писати латиницею (4ki1), викладача — за початком прізвища, аудиторію — за номером.
      </p>

      <ul
        id={listId}
        role="listbox"
        aria-label="Результати пошуку"
        hidden={!open || results.length === 0}
        className="flex flex-col overflow-hidden rounded-panel border border-default bg-surface"
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
        <p className="flex items-center gap-2 rounded-panel border border-default bg-surface px-3.5 py-3 text-ui text-secondary">
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
        <div className="flex flex-col gap-2.5">
          {favorites.length > 0 ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="me-1 text-caption font-semibold text-muted">Обране</span>
              {favorites.map((target) => (
                <TargetChip key={`${target.type}-${target.id}`} target={target} favorite />
              ))}
            </div>
          ) : null}
          {shortcuts.length > 0 ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="me-1 text-caption font-semibold text-muted">Нещодавні</span>
              {shortcuts.slice(0, 6).map((target) => (
                <TargetChip key={`${target.type}-${target.id}`} target={target} />
              ))}
              <button
                type="button"
                onClick={forgetRecent}
                className="rounded-control px-2 py-1 text-caption text-muted underline-offset-4 hover:text-primary hover:underline focus-visible:outline-2 focus-visible:outline-focus"
              >
                Очистити
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
