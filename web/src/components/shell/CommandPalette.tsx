import { Command } from 'cmdk';
import { CornerDownLeft, DoorOpen, History, Monitor, Moon, Search, Sun, UserRound, Users, type LucideIcon } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { usePublicSearch } from '@/api/meta';
import type { EntityType } from '@/api/types';
import { ACTIVE_ROUTES, paths } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { cn } from '@/lib/cn';
import { fuzzyFilter, groupCodeVariants } from '@/lib/search';
import { useCommandStore } from '@/stores/commands';
import { useFavoritesStore } from '@/stores/favorites';
import { useThemeStore } from '@/stores/theme';
import { KbdCombo } from '../ui/Kbd';
import { Plate } from '../ui/Plate';
import { Spinner } from '../ui/Spinner';

const ENTITY_ICON: Record<EntityType, LucideIcon> = { GROUP: Users, TEACHER: UserRound, ROOM: DoorOpen };
const ENTITY_LABEL: Record<EntityType, string> = { GROUP: 'група', TEACHER: 'викладач', ROOM: 'аудиторія' };

function Item({
  value,
  onSelect,
  icon: Icon,
  children,
  hint,
}: {
  value: string;
  onSelect: () => void;
  icon?: LucideIcon;
  children: ReactNode;
  hint?: ReactNode;
}) {
  return (
    <Command.Item
      value={value}
      onSelect={onSelect}
      className={cn(
        'group flex cursor-default select-none items-center gap-3 rounded-control px-3 py-2.5 text-ui text-primary outline-none',
        'data-[selected=true]:bg-selected data-[selected=true]:text-primary',
      )}
    >
      {Icon ? <Icon aria-hidden="true" className="size-4 shrink-0 text-muted group-data-[selected=true]:text-accent" /> : null}
      <span className="flex min-w-0 flex-1 items-baseline gap-2">{children}</span>
      {hint ? <span className="shrink-0 text-caption text-muted">{hint}</span> : null}
    </Command.Item>
  );
}

const GROUP_HEADING =
  '[&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pt-3 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-caption [&_[cmdk-group-heading]]:font-semibold [&_[cmdk-group-heading]]:text-muted';

/**
 * Командна палітра (Ctrl/⌘+K): розклад групи/викладача/аудиторії, сторінки, дії сторінки, тема.
 * На телефоні це ж вікно відкриває вкладка «Пошук». Код вантажиться лише при першому відкритті
 * (див. CommandPaletteHost).
 */
export function CommandPalette() {
  const open = useCommandStore((state) => state.open);
  const initialQuery = useCommandStore((state) => state.initialQuery);
  const setOpen = useCommandStore((state) => state.setOpen);

  return (
    <Command.Dialog
      open={open}
      onOpenChange={(next) => setOpen(next)}
      label="Пошук і команди"
      shouldFilter={false}
      loop
      overlayClassName="anim-overlay fixed inset-0 z-50 bg-scrim"
      contentClassName={cn(
        'anim-dialog fixed inset-x-2 top-2 z-50 mx-auto flex max-h-[calc(100dvh-1rem)] max-w-xl flex-col overflow-hidden',
        'rounded-dialog border border-default bg-raised text-primary shadow-dialog sm:top-[12vh] sm:max-h-[70vh]',
      )}
    >
      {open ? <PaletteBody key={initialQuery} initialQuery={initialQuery} onClose={() => setOpen(false)} /> : null}
    </Command.Dialog>
  );
}

function PaletteBody({ initialQuery, onClose }: { initialQuery: string; onClose: () => void }) {
  const navigate = useNavigate();
  const { hasRole } = useCurrentUser();
  const [query, setQuery] = useState(initialQuery);
  const debounced = useDebouncedValue(query, 180);
  const search = usePublicSearch(debounced, 8);
  const commands = useCommandStore((state) => state.commands);
  const recent = useFavoritesStore((state) => state.recent);
  const favorites = useFavoritesStore((state) => state.favorites);
  const setPreference = useThemeStore((state) => state.setPreference);

  const go = (to: string) => {
    onClose();
    void navigate(to);
  };
  const run = (action: () => void) => {
    onClose();
    action();
  };

  const trimmed = query.trim();
  const codes = useMemo(() => groupCodeVariants(trimmed), [trimmed]);
  const results = search.data ?? [];
  const resultCodes = new Set(results.filter((r) => r.type === 'GROUP').map((r) => r.label.toLocaleUpperCase('uk')));
  const directCodes = codes.filter((code) => !resultCodes.has(code));

  const pages = useMemo(
    () =>
      fuzzyFilter(
        ACTIVE_ROUTES.filter((route) => route.nav && (!route.roles || hasRole(...route.roles))),
        trimmed,
        (route) => [route.title, route.nav?.label, ...(route.nav?.keywords ?? [])],
      ).slice(0, trimmed ? 6 : 8),
    [trimmed, hasRole],
  );
  const actions = useMemo(
    () => fuzzyFilter(Object.values(commands), trimmed, (c) => [c.label, c.group, ...(c.keywords ?? [])]),
    [commands, trimmed],
  );
  const shortcuts = useMemo(() => {
    const seen = new Set<string>();
    return [...favorites, ...recent].filter((t) => {
      const key = `${t.type}:${t.id}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [favorites, recent]);
  const localTargets = useMemo(() => fuzzyFilter(shortcuts, trimmed, (t) => [t.label]).slice(0, 5), [shortcuts, trimmed]);
  const themes = fuzzyFilter(
    [
      { id: 'light', label: 'Світла тема', icon: Sun },
      { id: 'dark', label: 'Темна тема', icon: Moon },
      { id: 'system', label: 'Тема як у системі', icon: Monitor },
    ] as const,
    trimmed,
    (t) => [t.label, 'тема', 'оформлення'],
  );

  const searching = trimmed.length > 0 && (search.isFetching || debounced !== query);
  const nothing =
    trimmed.length > 0 &&
    !searching &&
    results.length === 0 &&
    directCodes.length === 0 &&
    pages.length === 0 &&
    actions.length === 0 &&
    localTargets.length === 0 &&
    themes.length === 0;

  return (
    <>
      <div className="flex items-center gap-3 border-b border-separator px-4">
        <Search aria-hidden="true" className="size-[1.125rem] text-muted" />
        <Command.Input
          value={query}
          onValueChange={setQuery}
          placeholder="Група, викладач, аудиторія або сторінка"
          className="h-14 w-full bg-transparent text-body text-primary outline-none placeholder:text-muted"
        />
        {searching ? <Spinner size="sm" className="text-muted" label="Шукаємо" /> : null}
      </div>
      <Command.List className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
        {nothing ? (
          <p className="px-3 py-8 text-center text-ui text-secondary">
            Нічого не знайшли за «{trimmed}». Спробуйте код групи (4КІ1), прізвище або номер аудиторії.
          </p>
        ) : null}

        {directCodes.length > 0 || results.length > 0 ? (
          <Command.Group heading="Розклад" className={GROUP_HEADING}>
            {directCodes.map((code) => (
              <Item key={`code-${code}`} value={`code-${code}`} icon={Users} onSelect={() => go(paths.group(code))} hint="група">
                <Plate size="md" tone="accent">
                  {code}
                </Plate>
                <span className="truncate text-caption text-muted">відкрити розклад групи</span>
              </Item>
            ))}
            {results.map((result) => (
              <Item
                key={`${result.type}-${result.id}`}
                value={`${result.type}-${result.id}`}
                icon={ENTITY_ICON[result.type]}
                onSelect={() => go(result.url)}
                hint={ENTITY_LABEL[result.type]}
              >
                <span className="truncate font-semibold">{result.label}</span>
                {result.sublabel ? <span className="truncate text-caption text-muted">{result.sublabel}</span> : null}
              </Item>
            ))}
          </Command.Group>
        ) : null}

        {search.isError && trimmed.length > 0 ? (
          <p className="px-3 py-2 text-caption text-muted">
            Пошук на сервері зараз недоступний. Код групи все одно можна відкрити напряму.
          </p>
        ) : null}

        {localTargets.length > 0 ? (
          <Command.Group heading={trimmed ? 'Збережені й нещодавні' : 'Нещодавні'} className={GROUP_HEADING}>
            {localTargets.map((target) => (
              <Item
                key={`recent-${target.type}-${target.id}`}
                value={`recent-${target.type}-${target.id}`}
                icon={History}
                onSelect={() => go(target.url)}
                hint={ENTITY_LABEL[target.type]}
              >
                <span className="truncate">{target.label}</span>
              </Item>
            ))}
          </Command.Group>
        ) : null}

        {actions.length > 0 ? (
          <Command.Group heading="Дії на сторінці" className={GROUP_HEADING}>
            {actions.map((command) => (
              <Item
                key={`cmd-${command.scope}-${command.id}`}
                value={`cmd-${command.scope}-${command.id}`}
                icon={command.icon}
                onSelect={() => run(command.perform)}
                hint={command.shortcut ? <KbdCombo keys={command.shortcut} /> : command.hint}
              >
                <span className="truncate">{command.label}</span>
              </Item>
            ))}
          </Command.Group>
        ) : null}

        {pages.length > 0 ? (
          <Command.Group heading="Сторінки" className={GROUP_HEADING}>
            {pages.map((route) => (
              <Item
                key={`page-${route.id}`}
                value={`page-${route.id}`}
                icon={route.nav?.icon}
                onSelect={() => go(route.path)}
                hint={route.layout === 'admin' ? 'диспетчер' : route.layout === 'teacher' ? 'кабінет' : undefined}
              >
                <span className="truncate">{route.title}</span>
              </Item>
            ))}
          </Command.Group>
        ) : null}

        {trimmed && themes.length > 0 ? (
          <Command.Group heading="Оформлення" className={GROUP_HEADING}>
            {themes.map((theme) => (
              <Item key={`theme-${theme.id}`} value={`theme-${theme.id}`} icon={theme.icon} onSelect={() => run(() => setPreference(theme.id))}>
                {theme.label}
              </Item>
            ))}
          </Command.Group>
        ) : null}
      </Command.List>
      <div className="hidden items-center gap-4 border-t border-separator bg-sunken px-4 py-2 text-caption text-muted sm:flex">
        <span className="flex items-center gap-1.5">
          <KbdCombo keys="arrowup" />
          <KbdCombo keys="arrowdown" /> вибрати
        </span>
        <span className="flex items-center gap-1.5">
          <CornerDownLeft aria-hidden="true" className="size-3.5" /> відкрити
        </span>
        <span className="flex items-center gap-1.5">
          <KbdCombo keys="esc" /> закрити
        </span>
      </div>
    </>
  );
}
