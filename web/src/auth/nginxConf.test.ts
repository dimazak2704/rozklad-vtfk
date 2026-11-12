// @vitest-environment node
import { describe, expect, it } from 'vitest';

// nginx.conf читаємо як текст (типів node у src немає, тож fs беремо динамічно, як у silentPage.test.ts)
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
};
const nginx = readFileSync(new URL('../../nginx.conf', import.meta.url), 'utf8');

/** Правила `map $request_uri $rozklad_log_path { … }` як пари [регулярний вираз, значення]. */
function logPathRules(): [RegExp, string][] {
  const start = nginx.indexOf('map $request_uri $rozklad_log_path {');
  expect(start).toBeGreaterThanOrEqual(0);
  const body = nginx.slice(nginx.indexOf('{', start) + 1, nginx.indexOf('}', start));
  return body
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('~'))
    .map((line) => {
      const [pattern = '', value = ''] = line.replace(/;$/, '').split(/\s+/);
      const insensitive = pattern.startsWith('~*');
      return [new RegExp(pattern.replace(/^~\*?/, ''), insensitive ? 'i' : ''), value];
    });
}

/** Що nginx запише в журнал замість шляху запиту (перше правило, що збіглося). */
function loggedPath(requestUri: string): string {
  for (const [pattern, value] of logPathRules()) {
    const match = pattern.exec(requestUri);
    if (match) return value.replace(/\$(\w+)/g, (_, name: string) => match.groups?.[name] ?? '');
  }
  return requestUri;
}

describe('журнал nginx не містить секретів', () => {
  it('токени посилань запрошення і пароля замінено, query string відкинуто', () => {
    expect(loggedPath('/password/AAAAsecretToken')).toBe('/password/[token]');
    expect(loggedPath('/invite/AAAAsecretToken?from=mail')).toBe('/invite/[token]');
    expect(loggedPath('/Invite/AAAAsecretToken')).toBe('/Invite/[token]');
    expect(loggedPath('//password/AAAAsecretToken')).toBe('/password/[token]');
    expect(loggedPath('/login/callback?code=abc&state=xyz')).toBe('/login/callback');
    expect(loggedPath('/auth/connect/logout?id_token_hint=eyJ')).toBe('/auth/connect/logout');
    expect(loggedPath('/admin/access')).toBe('/admin/access');
  });

  it('сервер пише журнал лише у форматі із замаскованим шляхом', () => {
    expect(nginx).toMatch(/^ {2}access_log \/var\/log\/nginx\/access\.log rozklad_safe;$/m);
    const formatStart = nginx.indexOf('log_format rozklad_safe');
    const format = nginx.slice(formatStart, nginx.indexOf(';', formatStart));
    expect(format).toContain('$rozklad_log_path');
    expect(format).not.toMatch(/\$request[^_]|\$request_uri|\$uri|\$args|\$query_string/);
    const logs = [...nginx.matchAll(/access_log\s+([^;]+);/g)].map((match) => match[1]);
    expect(logs.every((value) => value === 'off' || value?.endsWith(' rozklad_safe'))).toBe(true);
  });
});

describe('CSP сайту', () => {
  it('дозволяє шрифти data:, які Vite вбудовує в CSS', () => {
    const csp = /add_header Content-Security-Policy "([^"]+)"/.exec(nginx)?.[1] ?? '';
    expect(csp).toContain("font-src 'self' data:;");
  });
});
