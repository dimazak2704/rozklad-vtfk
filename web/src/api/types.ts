/**
 * TypeScript-типи для DTO з docs/CONTRACTS.md (REST + SSE).
 *
 * Правила:
 * - Поля й назви збігаються з контрактом буквально (camelCase, ISO-дати, `HH:mm`, UUID-рядки).
 * - Поле з `?` у контракті тут теж необовʼязкове й може прийти як `null` (Jackson пише null).
 * - Де контракт не описує поля (CRUD каталогу, частина адмін-відповідей), тип виведено з
 *   docs/DOMAIN.md і позначено `@inferred`. Агент, що володіє цією областю, може уточнити саме цей блок.
 *
 * Типи розкладені за доменами в `./types/`; цей файл лише реекспортує їх,
 * тому імпорти з `api/types` по всьому застосунку не змінюються.
 */

export type * from './types/common';
export type * from './types/public';
export type * from './types/catalog';
export type * from './types/calendar';
export type * from './types/load';
export type * from './types/timetable';
export type * from './types/generation';
export type * from './types/changes';
export type * from './types/orders';
export type * from './types/accounting';
export type * from './types/identity';
export type * from './types/core-import';
export type * from './types/teacher';
export type * from './types/internal';
export type * from './types/importer';
export type * from './types/notification';
export type * from './types/email';
export type * from './types/sse';
