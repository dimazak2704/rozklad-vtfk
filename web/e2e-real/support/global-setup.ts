import { request } from '@playwright/test';
import { AUTH_URL, GATEWAY } from './real';

/** Стенд має бути піднятий і заповнений: інакше кожен тест падав би з незрозумілою причиною. */
export default async function globalSetup(): Promise<void> {
  const api = await request.newContext();
  try {
    const checks: Array<[string, string]> = [
      [`${GATEWAY}/api/public/meta`, 'gateway і core (scripts/dev-stack.sh up)'],
      [`${AUTH_URL}/.well-known/openid-configuration`, 'сервіс входу auth через nginx контейнера web (scripts/dev-stack.sh up)'],
    ];
    for (const [url, what] of checks) {
      const response = await api.get(url, { timeout: 10_000 }).catch(() => null);
      if (!response?.ok()) throw new Error(`Стенд недоступний: ${url} (${response?.status() ?? 'немає відповіді'}). Потрібен ${what}.`);
    }
    const meta = (await (await api.get(`${GATEWAY}/api/public/meta`)).json()) as { semester: unknown };
    if (!meta.semester) throw new Error('На стенді немає активного семестру: scripts/dev-stack.sh seed');
  } finally {
    await api.dispose();
  }
}
