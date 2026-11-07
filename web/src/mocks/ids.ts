import type { Uuid } from '@/api/types';

/** 32-бітний хеш FNV-1a з «перемішуванням» murmur3 — швидкий і стабільний між запусками. */
function hash32(text: string, seed: number): number {
  let h = (0x811c9dc5 ^ seed) >>> 0;
  for (let i = 0; i < text.length; i += 1) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h >>> 0;
}

const SEEDS = [0x9e3779b9, 0x7f4a7c15, 0x94d049bb, 0xbf58476d] as const;

/**
 * Детермінований UUID для мок-даних: той самий `kind` + `key` дають той самий id у кожному запуску,
 * тож посилання `/t/<id>` і `/r/<id>` не ламаються після перезавантаження. Формат — UUIDv8 (RFC 9562).
 */
export function mockId(kind: string, key: string): Uuid {
  const text = `${kind}:${key}`;
  const hex = SEEDS.map((seed) => hash32(text, seed).toString(16).padStart(8, '0')).join('');
  const variant = ((Number.parseInt(hex.charAt(16), 16) & 0x3) | 0x8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-8${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}
