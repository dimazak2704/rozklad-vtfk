// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Як authPageCss.test.ts: файли читаємо як текст, модуль fs — динамічно.
const fsModule = 'node:fs';
const { readFileSync, readdirSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
  readdirSync: (path: URL) => string[];
};
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const authCss = read('../styles/auth-page.css');
const templatesDir = '../../../services/auth/src/main/resources/templates/';
// усі шаблони auth-сервера з підвалом: login.html, error.html і будь-який новий
const footerTemplates = readdirSync(new URL(templatesDir, import.meta.url))
  .filter((name) => name.endsWith('.html'))
  .map((name) => [name, read(templatesDir + name)] as const)
  .filter(([, html]) => html.includes('<footer class="auth-footer">'));

/** Підвал сторінок входу: «← До розкладу · Вінницький технічний фаховий коледж». */
describe('підвал сторінок входу', () => {
  it('підвал є щонайменше на сторінці входу і сторінці помилки', () => {
    expect(footerTemplates.map(([name]) => name)).toEqual(
      expect.arrayContaining(['login.html', 'error.html']),
    );
  });

  it.each(footerTemplates)(
    '%s: кожна крапка-розділювач має свій клас і схована від читачів екрана',
    (_name, html) => {
      const footer = /<footer class="auth-footer">([\s\S]*?)<\/footer>/.exec(html)?.[1] ?? '';
      const dots = footer.match(/<span[^>]*>·<\/span>/g) ?? [];
      expect(dots.length).toBeGreaterThan(0);
      // без класу крапка лишиться у вузькому стовпчику окремим рядком
      for (const dot of dots) {
        expect(dot).toBe('<span class="auth-footer-sep" aria-hidden="true">·</span>');
      }
    },
  );

  it('на вузькому екрані підвал стає стовпчиком без крапки: «·» не висить у кінці першого рядка', () => {
    const narrow = /@media \(max-width: ([\d.]+)em\) \{([\s\S]*?)\n\}/.exec(authCss);
    expect(narrow, 'медіазапит вузького підвалу').not.toBeNull();
    const [, width, body] = narrow ?? [];
    // весь підвал в один рядок займає ~22em: межа має бути не вужча, інакше крапка знову повисне
    expect(Number(width)).toBeGreaterThanOrEqual(24);
    expect(body).toMatch(/\.auth-footer \{[^}]*flex-direction: column;/);
    expect(body).toMatch(/\.auth-footer-sep \{[^}]*display: none;/);
  });
});
