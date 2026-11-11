import { Command } from 'cmdk';
import { DoorOpen, History, Monitor, Moon, Search, Sun, UserRound, Users, type LucideIcon } from 'lucide-react';
import { Dialog as RadixDialog } from 'radix-ui';
import { useMemo, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { usePublicSearch } from '@/api/meta';
import type { EntityType } from '@/api/types';
import { ACTIVE_ROUTES, paths, type AppRoute, type LayoutKind } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { routeForPath } from '@/hooks/useBackLink';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { cn } from '@/lib/cn';
import { fuzzyFilter, groupCodeVariants, isFullGroupCode } from '@/lib/search';
import { useCommandStore } from '@/stores/commands';
import { useFavoritesStore } from '@/stores/favorites';
import { useThemeStore } from '@/stores/theme';
import { KbdCombo } from '../ui/Kbd';
import { Plate } from '../ui/Plate';
import { useReturnFocus } from '../ui/returnFocus';
import { Spinner } from '../ui/Spinner';

const ENTITY_ICON: Record<EntityType, LucideIcon> = { GROUP: Users, TEACHER: UserRound, ROOM: DoorOpen };

/** Розділ, у якому людина зараз: його сторінки в палітрі йдуть першими. */
const LAYOUT_RANK: Record<LayoutKind, Record<LayoutKind, number>> = {
  admin: { admin: 0, teacher: 1, public: 2, bare: 3 },
  teacher: { teacher: 0, public: 1, admin: 2, bare: 3 },
  public: { public: 0, teacher: 1, admin: 2, bare: 3 },
  bare: { public: 0, teacher: 1, admin: 2, bare: 3 },
};

/** Підказка праворуч від сторінки іншого розділу: «Заміни · диспетчер» і «Заміни · публічна» не сплутати. */
function pageHint(route: AppRoute, current: LayoutKind): string | undefined {
  if (route.layout === current) return undefined;
  if (route.layout === 'admin') return 'диспетчер';
  if (route.layout === 'teacher') return 'кабінет';
  return current === 'public' || current === 'bare' ? undefined : 'публічна';
}

function Item({
  value,
  onSelect,
  icon: Icon,
  children,
  sublabel,
  hint,
}: {
  value: string;
  onSelect: () => void;
  icon?: LucideIcon;
  /** Головне (код групи, прізвище, назва сторінки): ніколи не обрізається. */
  children: ReactNode;
  /** Уточнення приглушене; обрізається першим, а на телефоні переходить на другий рядок. */
  sublabel?: ReactNode;
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
      <span className="flex min-w-0 flex-1 flex-col sm:flex-row sm:items-baseline sm:gap-2">
        <span className="shrink-0">{children}</span>
        {sublabel ? <span className="min-w-0 truncate text-caption text-muted">{sublabel}</span> : null}
      </span>
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
  // власне вікно Radix замість Command.Dialog: тому не передати обробники фокуса, а без них
  // після Esc фокус падав на <body>
  const returnFocus = useReturnFocus();

  return (
    <RadixDialog.Root open={open} onOpenChange={(next) => setOpen(next)}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="anim-overlay fixed inset-0 z-50 bg-scrim" />
        <RadixDialog.Content
          aria-describedby={undefined}
          className={cn(
            'anim-dialog fixed inset-x-2 top-2 z-50 mx-auto flex max-h-[calc(100dvh-1rem)] max-w-xl flex-col overflow-hidden',
            'rounded-dialog border border-default bg-raised text-primary shadow-dialog sm:top-[12vh] sm:max-h-[70vh]',
          )}
          {...returnFocus}
        >
          <RadixDialog.Title className="sr-only">Пошук і команди</RadixDialog.Title>
          <Command label="Пошук і команди" shouldFilter={false} loop>
            {open ? <PaletteBody key={initialQuery} initialQuery={initialQuery} onClose={() => setOpen(false)} /> : null}
          </Command>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}

function PaletteBody({ initialQuery, onClose }: { initialQuery: string; onClose: () => void }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const layout: LayoutKind = routeForPath(pathname)?.layout ?? (pathname.startsWith('/admin') ? 'admin' : 'public');
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
  // «відкрити розклад групи» — лише для повного коду (4КІ1) або коли пошук на сервері впав: «4К» вів на «групи немає»
  const directCodes = codes.filter((code) => !resultCodes.has(code) && (isFullGroupCode(code) || search.isError));

  const pages = useMemo(() => {
    const available = ACTIVE_ROUTES.filter((route) => route.nav && (!route.roles || hasRole(...route.roles)));
    const rank = LAYOUT_RANK[layout];
    const ordered = [...available].sort((a, b) => rank[a.layout] - rank[b.layout] || (a.nav?.order ?? 0) - (b.nav?.order ?? 0));
    if (trimmed) {
      return fuzzyFilter(ordered, trimmed, (route) => [route.title, route.nav?.label, ...(route.nav?.keywords ?? [])]).slice(0, 6);
    }
    // без запиту — спершу розділ, у якому людина: у панелі диспетчера це всі її розділи, далі решта
    return ordered.slice(0, layout === 'admin' ? 20 : 8);
  }, [trimmed, hasRole, layout]);
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
              <Item
                key={`code-${code}`}
                value={`code-${code}`}
                icon={Users}
                onSelect={() => go(paths.group(code))}
                sublabel="відкрити розклад групи"
              >
                <Plate size="md" tone="accent">
                  {code}
                </Plate>
              </Item>
            ))}
            {results.map((result) => (
              <Item
                key={`${result.type}-${result.id}`}
                value={`${result.type}-${result.id}`}
                icon={ENTITY_ICON[result.type]}
                onSelect={() => go(result.url)}
                sublabel={result.sublabel}
              >
                <span className="font-semibold">{result.label}</span>
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
              >
                {target.label}
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
                {command.label}
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
                hint={pageHint(route, layout)}
              >
                {route.title}
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
    </>
  );
}
