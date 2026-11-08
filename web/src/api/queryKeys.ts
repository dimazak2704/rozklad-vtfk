/**
 * Фабрика ключів TanStack Query. Ключі ієрархічні, тож інвалідація області працює префіксом:
 * `queryClient.invalidateQueries({ queryKey: qk.admin.changes.all })`.
 * Нові області додавайте сюди (одна гілка на область), а не пишіть ключі в компонентах.
 */
import type { EntityType, IsoDate, Uuid } from './types';

type Params = Record<string, unknown>;

/** Прибирає undefined, щоб `{a: undefined}` і `{}` давали той самий ключ. */
function clean<T extends Params>(params: T | undefined): Partial<T> {
  if (!params) return {};
  return Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined)) as Partial<T>;
}

export const qk = {
  public: {
    all: ['public'] as const,
    meta: () => ['public', 'meta'] as const,
    search: (q: string) => ['public', 'search', q] as const,
    groups: () => ['public', 'groups'] as const,
    teachers: () => ['public', 'teachers'] as const,
    rooms: () => ['public', 'rooms'] as const,
    bells: (date?: IsoDate) => ['public', 'bells', date ?? 'profiles'] as const,
    scheduleAll: ['public', 'schedule'] as const,
    schedule: (owner: EntityType, id: Uuid, from: IsoDate, to: IsoDate) =>
      ['public', 'schedule', owner, id, { from, to }] as const,
    changes: (date: IsoDate) => ['public', 'changes', date] as const,
    invite: (token: string) => ['public', 'invite', token] as const,
  },
  me: {
    all: ['me'] as const,
    profile: () => ['me', 'profile'] as const,
    schedule: (from: IsoDate, to: IsoDate) => ['me', 'schedule', { from, to }] as const,
    absences: () => ['me', 'absences'] as const,
    changes: (status?: string) => ['me', 'changes', clean({ status })] as const,
    preferences: (semesterId?: Uuid) => ['me', 'preferences', clean({ semesterId })] as const,
  },
  admin: {
    all: ['admin'] as const,
    dashboard: (date: IsoDate) => ['admin', 'dashboard', date] as const,
    absences: {
      all: ['admin', 'absences'] as const,
      list: (params?: Params) => ['admin', 'absences', 'list', clean(params)] as const,
      affected: (absenceId: Uuid) => ['admin', 'absences', 'affected', absenceId] as const,
    },
    lessonOptions: (lessonId: Uuid) => ['admin', 'lesson-options', lessonId] as const,
    changes: {
      all: ['admin', 'changes'] as const,
      list: (params?: Params) => ['admin', 'changes', 'list', clean(params)] as const,
    },
    orders: {
      all: ['admin', 'orders'] as const,
      list: (params?: Params) => ['admin', 'orders', 'list', clean(params)] as const,
      detail: (id: Uuid) => ['admin', 'orders', 'detail', id] as const,
      draft: (date: IsoDate) => ['admin', 'orders', 'draft', date] as const,
      numbering: (semesterId: Uuid) => ['admin', 'orders', 'numbering', semesterId] as const,
    },
    timetable: {
      all: ['admin', 'timetable'] as const,
      versions: (semesterId?: Uuid) => ['admin', 'timetable', 'versions', clean({ semesterId })] as const,
      lessons: (versionId: Uuid, filters?: Params) =>
        ['admin', 'timetable', 'lessons', versionId, clean(filters)] as const,
      conflicts: (versionId: Uuid) => ['admin', 'timetable', 'conflicts', versionId] as const,
      metrics: (versionId: Uuid) => ['admin', 'timetable', 'metrics', versionId] as const,
      diff: (a: Uuid, b: Uuid) => ['admin', 'timetable', 'diff', a, b] as const,
    },
    generation: {
      all: ['admin', 'generation'] as const,
      jobs: (semesterId?: Uuid) => ['admin', 'generation', 'jobs', clean({ semesterId })] as const,
      job: (jobId: Uuid) => ['admin', 'generation', 'job', jobId] as const,
    },
    load: (params?: Params) => ['admin', 'load', clean(params)] as const,
    accounting: {
      all: ['admin', 'accounting'] as const,
      rows: (params?: Params) => ['admin', 'accounting', 'rows', clean(params)] as const,
      teachers: (month: string) => ['admin', 'accounting', 'teachers', month] as const,
    },
    catalog: {
      all: ['admin', 'catalog'] as const,
      list: (entity: string, params?: Params) => ['admin', 'catalog', entity, 'list', clean(params)] as const,
      detail: (entity: string, id: Uuid) => ['admin', 'catalog', entity, 'detail', id] as const,
    },
    calendar: {
      all: ['admin', 'calendar'] as const,
      semesters: () => ['admin', 'calendar', 'semesters'] as const,
      weeks: (semesterId: Uuid) => ['admin', 'calendar', 'weeks', semesterId] as const,
      bellProfiles: () => ['admin', 'calendar', 'bell-profiles'] as const,
      periods: (semesterId?: Uuid) => ['admin', 'calendar', 'periods', clean({ semesterId })] as const,
      shiftWindows: () => ['admin', 'calendar', 'shift-windows'] as const,
    },
    settings: () => ['admin', 'settings'] as const,
    invites: () => ['admin', 'invites'] as const,
  },
  importer: {
    all: ['importer'] as const,
    jobs: (params?: Params) => ['importer', 'jobs', clean(params)] as const,
    job: (id: Uuid) => ['importer', 'job', id] as const,
    aiUsage: () => ['importer', 'ai-usage'] as const,
  },
  notify: {
    all: ['notify'] as const,
    bot: () => ['notify', 'bot'] as const,
    bindings: () => ['notify', 'bindings'] as const,
    myBinding: () => ['notify', 'bindings', 'me'] as const,
    deliveries: (params?: Params) => ['notify', 'deliveries', clean(params)] as const,
  },
} as const;
