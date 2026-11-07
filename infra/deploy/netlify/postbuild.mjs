#!/usr/bin/env node
/**
 * Netlify post-build step of the "SPA on Netlify, API and Keycloak on the VPS" layout
 * (web/netlify.toml, docs/DEPLOY.md). Runs in web/ after `npm run build`:
 *
 *   node ../infra/deploy/netlify/postbuild.mjs [dist]
 *
 * - Writes dist/_headers with the Content-Security-Policy of web/nginx.conf adapted to this layout:
 *   the development Keycloak (http://localhost:8180) becomes the origin of VITE_OIDC_AUTHORITY,
 *   the origin of VITE_API_BASE is allowed for fetch/SSE, images and frames, and the script hashes
 *   are computed from the built index.html. One policy, no copy to keep in sync by hand.
 * - Deletes the source maps from dist (the nginx image does not serve them either).
 */
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const webDir = resolve(import.meta.dirname, '../../../web');
const distDir = resolve(webDir, process.argv[2] ?? 'dist');
const DEV_KEYCLOAK = 'http://localhost:8180';

function originOf(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set: add it in Netlify, Site configuration > Environment variables`);
  }
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} must be an absolute https URL in this layout, got "${value}"`);
  }
  if (url.protocol !== 'https:') {
    throw new Error(`${name} must use https, got "${value}"`);
  }
  return url.origin;
}

const authOrigin = originOf('VITE_OIDC_AUTHORITY');
const apiOrigin = originOf('VITE_API_BASE');

const nginx = readFileSync(join(webDir, 'nginx.conf'), 'utf8');
const policy = nginx.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"/)?.[1];
if (!policy) {
  throw new Error('web/nginx.conf has no Content-Security-Policy header to adapt');
}

const html = readFileSync(join(distDir, 'index.html'), 'utf8');
const inlineHashes = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(
  ([, code]) => `'sha256-${createHash('sha256').update(code, 'utf8').digest('base64')}'`,
);

const directives = new Map(
  policy
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const [name, ...sources] = part.split(/\s+/);
      return [name, sources.map((source) => (source === DEV_KEYCLOAK ? authOrigin : source))];
    }),
);

function allow(name, ...sources) {
  const current = directives.get(name) ?? directives.get('default-src') ?? ["'self'"];
  directives.set(name, [...new Set([...current, ...sources])]);
}

// nginx's script sources without its hash, plus the hashes of this build's inline scripts
const scriptSources = (directives.get('script-src') ?? ["'self'"]).filter((s) => !s.startsWith("'sha256-"));
directives.set('script-src', [...scriptSources, ...inlineHashes]);
allow('connect-src', apiOrigin, authOrigin);
allow('img-src', apiOrigin);
allow('frame-src', apiOrigin);
allow('form-action', authOrigin);

const csp = [...directives].map(([name, sources]) => [name, ...sources].join(' ')).join('; ');
writeFileSync(join(distDir, '_headers'), `/*\n  Content-Security-Policy: ${csp}\n`);

let removedMaps = 0;
function removeSourceMaps(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      removeSourceMaps(path);
    } else if (entry.name.endsWith('.map')) {
      rmSync(path);
      removedMaps += 1;
    }
  }
}
removeSourceMaps(distDir);

console.log(`postbuild: _headers with a CSP for ${apiOrigin} and ${authOrigin}; ${removedMaps} source maps removed`);
