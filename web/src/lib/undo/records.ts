/**
 * Спільна «адреса» запису для всіх дій журналу над ним: поточний id і версія.
 *
 * Повтор створення додає запис знову — з новим id. Якби кожна дія тримала власний id, наступна
 * правка того самого запису стукала б у вже видалений (404) і журнал звинуватив би когось іншого.
 * Тому всі записи журналу про один запис ділять одне посилання: повтор створення переносить його
 * на новий id, кожна вдала правка — оновлює версію. Чужу правку це не ховає: версію знає лише
 * журнал, і сервер на неї відповість 409.
 */
import type { QueryKey } from '@tanstack/react-query';
import { notify } from '@/components/ui/toast';
import { track } from './actions';
import { JournalBlockedError } from './errors';
import type { JournalEntry } from './journal';

export interface RecordRef {
  readonly kind: string;
  id: string;
  version: number | undefined;
  /** Підпис дії, чиє скасування прибрало запис («Додано аудиторію 305»), поки її не повернуто. */
  removedBy?: string;
}

const refs = new Map<string, RecordRef>();
const keyOf = (kind: string, id: string) => `${kind}:${id}`;

/** Посилання на запис (спільне для всіх дій журналу над ним); `version` — щойно отримана від сервера. */
export function recordRef(kind: string, id: string, version: number | undefined): RecordRef {
  const existing = refs.get(keyOf(kind, id));
  if (existing) {
    existing.version = version;
    return existing;
  }
  const ref: RecordRef = { kind, id, version };
  refs.set(keyOf(kind, id), ref);
  return ref;
}

/** Запис створено знову з новим id. */
export function moveRecord(ref: RecordRef, id: string, version: number | undefined): void {
  refs.delete(keyOf(ref.kind, ref.id));
  ref.id = id;
  ref.version = version;
  ref.removedBy = undefined;
  refs.set(keyOf(ref.kind, id), ref);
}

/** Для тестів і зміни користувача. */
export function clearRecordRefs(): void {
  refs.clear();
}

/** Правку не можна застосувати: запис прибрало скасування його створення. */
export function assertPresent(ref: RecordRef): void {
  if (ref.removedBy) throw new JournalBlockedError(`Запис прибрано скасуванням «${ref.removedBy}». Спершу поверніть його.`, 'stale');
}

interface Versioned {
  id: string;
  version?: number;
}

export interface SavedRecordInput<Body extends { version?: number }> {
  /** Вид запису: `rooms`, `load`, `periods` — id різних видів не перетинаються. */
  kind: string;
  label: string;
  invalidate?: readonly QueryKey[];
  /** Тіло до правки; null — запис щойно створено. */
  before: Body | null;
  after: Body;
  saved: Versioned;
  create: (body: Body) => Promise<Versioned>;
  update: (id: string, body: Body) => Promise<Versioned>;
  remove: (id: string) => Promise<unknown>;
}

/**
 * Записує збереження форми в журнал: створення скасовується видаленням (повтор створює запис знову),
 * правка — поверненням попереднього тіла з версією, яку сервер дав після останньої дії журналу.
 */
export function trackSavedRecord<Body extends { version?: number }>(input: SavedRecordInput<Body>): JournalEntry {
  const { kind, label, invalidate, before, after, saved, create, update, remove } = input;
  const ref = recordRef(kind, saved.id, saved.version);
  if (before === null) {
    ref.removedBy = undefined;
    return track({
      label,
      invalidate,
      undo: async () => {
        await remove(ref.id);
        ref.removedBy = label;
      },
      redo: async () => {
        const again = await create({ ...after, version: undefined });
        moveRecord(ref, again.id, again.version);
      },
    });
  }
  const put = async (body: Body) => {
    assertPresent(ref);
    const result = await update(ref.id, { ...body, version: ref.version });
    ref.version = result.version;
  };
  return track({ label, invalidate, undo: () => put(before), redo: () => put(after) });
}

/**
 * Правку збережено без змін: закриваємо форму без запиту й без запису в журналі (інакше журнал
 * казав би про зміну, якої не було, а зайва версія давала б колегам 409). Повертає, чи пропущено.
 */
export function skipUnchanged(before: unknown, after: unknown, close: () => void): boolean {
  if (before === null || before === undefined || !sameRequest(before, after)) return false;
  notify.info('Змін немає — нічого не збережено', { duration: 2500 });
  close();
  return true;
}

/** Чи тіла однакові (без версії): збереження без змін не надсилаємо й не пишемо в журнал. */
export function sameRequest(a: unknown, b: unknown): boolean {
  return stableJson(a) === stableJson(b);
}

function stableJson(value: unknown): string {
  return JSON.stringify(value, (key, inner: unknown) => {
    if (key === 'version') return undefined;
    if (inner && typeof inner === 'object' && !Array.isArray(inner)) {
      return Object.fromEntries(
        Object.entries(inner as Record<string, unknown>)
          .filter(([, v]) => v !== undefined)
          .sort(([x], [y]) => x.localeCompare(y)),
      );
    }
    return inner;
  });
}
