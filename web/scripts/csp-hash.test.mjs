// @vitest-environment node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = resolve(import.meta.dirname, '..');

describe('CSP у nginx.conf', () => {
  it('дозволяє саме той вбудований скрипт теми, що є в index.html', () => {
    const html = readFileSync(resolve(ROOT, 'index.html'), 'utf8');
    const nginx = readFileSync(resolve(ROOT, 'nginx.conf'), 'utf8');
    const inline = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((match) => match[1] ?? '');
    expect(inline).toHaveLength(1);
    const hash = `sha256-${createHash('sha256').update(inline[0] ?? '', 'utf8').digest('base64')}`;
    expect(nginx).toContain(`'${hash}'`);
  });
});
