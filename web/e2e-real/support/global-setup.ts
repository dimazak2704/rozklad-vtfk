import { request } from '@playwright/test';
import { GATEWAY, KEYCLOAK } from './real';

/** Стенд має бути піднятий і заповнений: інакше кожен тест падав би з незрозумілою причиною. */
export default async function globalSetup(): Promise<void> {
  const api = await request.newContext();
  try {
    const checks: Array<[string, string]> = [
      [`${GATEWAY}/api/public/meta`, 'gateway і core (scripts/dev-stack.sh up --no-telegram)'],
      [`${KEYCLOAK}/realms/rozklad/.well-known/openid-configuration`, 'Keycloak з realm rozklad'],
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
