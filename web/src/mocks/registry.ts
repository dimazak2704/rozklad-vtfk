/**
 * Збирає обробники мок-API з усіх модулів `./handlers/*.ts` (кожен експортує масив `handlers`).
 * Нова область = новий файл у handlers/, реєструвати його ніде не треба.
 */
import type { RequestHandler } from 'msw';

interface HandlerModule {
  handlers?: unknown;
}

export function collectHandlers(modules: Record<string, HandlerModule>): RequestHandler[] {
  return Object.keys(modules)
    .sort()
    .flatMap((path) => {
      const list = modules[path]?.handlers;
      if (!Array.isArray(list)) {
        throw new Error(`Мок-модуль ${path} має експортувати масив handlers`);
      }
      return list as RequestHandler[];
    });
}

const modules = import.meta.glob<HandlerModule>(['./handlers/*.ts', '!./handlers/*.test.ts'], { eager: true });

/** Обробники всіх областей у сталому порядку (за назвою файлу). */
export const handlers: RequestHandler[] = collectHandlers(modules);
