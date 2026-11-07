/**
 * Нормалізація для пошуку (DOMAIN §10): NFC, нижній регістр, латинські двійники → кирилиця,
 * уніфіковані апострофи, канонічні коди груп. Плюс нечіткий збіг для локальних списків
 * і командної палітри. Сервер робить те саме для /api/public/search.
 */

/** Латинські літери, що виглядають як кириличні (DOMAIN §10). */
const LATIN_LOOKALIKES: Readonly<Record<string, string>> = {
  a: 'а',
  c: 'с',
  e: 'е',
  i: 'і',
  o: 'о',
  p: 'р',
  x: 'х',
  y: 'у',
  k: 'к',
  m: 'м',
  t: 'т',
  h: 'н',
  b: 'в',
};

/** Фонетична транслітерація для кодів груп: «2ob» людина пише, маючи на увазі «2ОБ». */
const LATIN_PHONETIC: Readonly<Record<string, string>> = {
  a: 'а', b: 'б', c: 'ц', d: 'д', e: 'е', f: 'ф', g: 'г', h: 'х', i: 'і', j: 'й', k: 'к', l: 'л', m: 'м',
  n: 'н', o: 'о', p: 'п', q: 'к', r: 'р', s: 'с', t: 'т', u: 'у', v: 'в', w: 'в', x: 'х', y: 'и', z: 'з',
};

/** Усі варіанти апострофа, які трапляються в іменах і назвах. */
const APOSTROPHES_RE = /['`ʼ’‘ʹ′´]/g;
const CYRILLIC_RE = /[\u0400-\u04ff]/;
const LATIN_RE = /[a-z]/;

/** Код групи: цифра курсу, 1–3 літери спеціальності, номер. Літери можуть бути латинськими двійниками. */
const GROUP_CODE_LOOSE_RE = /^([0-9іi|l])\s*([a-zа-яіїєґ]{1,3})\s*(\d?)$/iu;

/**
 * Розкладка клавіатури: користувач набрав «rhtywsy» замість «кренцін», бо забув перемкнути мову.
 * Відповідність фізичних клавіш англійської та української розкладок.
 */
const EN_TO_UK_LAYOUT: Readonly<Record<string, string>> = {
  q: 'й', w: 'ц', e: 'у', r: 'к', t: 'е', y: 'н', u: 'г', i: 'ш', o: 'щ', p: 'з', '[': 'х', ']': 'ї',
  a: 'ф', s: 'і', d: 'в', f: 'а', g: 'п', h: 'р', j: 'о', k: 'л', l: 'д', ';': 'ж', "'": 'є',
  z: 'я', x: 'ч', c: 'с', v: 'м', b: 'и', n: 'т', m: 'ь', ',': 'б', '.': 'ю', '`': 'ʼ',
};

function isMostlyCyrillic(token: string): boolean {
  let cyr = 0;
  let lat = 0;
  for (const ch of token) {
    if (CYRILLIC_RE.test(ch)) cyr += 1;
    else if (LATIN_RE.test(ch)) lat += 1;
  }
  return cyr > 0 && cyr >= lat;
}

function replaceLookalikes(token: string): string {
  let out = '';
  for (const ch of token) out += LATIN_LOOKALIKES[ch] ?? ch;
  return out;
}

function parseGroupCode(raw: string): { course: string; letters: string; number: string } | null {
  const compact = raw.normalize('NFC').trim().toLowerCase().replace(/[\s.\-_]+/g, '');
  const match = GROUP_CODE_LOOSE_RE.exec(compact);
  if (!match) return null;
  const [, courseRaw = '', letters = '', number = ''] = match;
  // На місці курсу кириличну «І», латинську «i», «l» чи «|» набирають замість одиниці
  const course = /[0-9]/.test(courseRaw) ? courseRaw : '1';
  return { course, letters, number };
}

function buildGroupCode(course: string, letters: string, number: string): string | null {
  // у кодах спеціальностей немає «И»: це російська розкладка замість «І»
  const fixed = letters.replace(/и/g, 'і');
  if (!/^[а-яіїєґ]+$/.test(fixed)) return null;
  return `${course}${fixed.toLocaleUpperCase('uk')}${number}`;
}

/**
 * Канонічний код групи (DOMAIN §10): «4ki1» → «4КІ1», «ІКІ1» → «1КІ1», «1ТК 1» → «1ТК1», «4КИ1» → «4КІ1».
 * Латинські літери замінюються візуальними двійниками. Повертає null, якщо рядок не схожий на код групи.
 */
export function canonicalGroupCode(raw: string): string | null {
  const parsed = parseGroupCode(raw);
  if (!parsed) return null;
  return buildGroupCode(parsed.course, replaceLookalikes(parsed.letters), parsed.number);
}

/** Усі правдоподібні коди групи для запиту: візуальні двійники і фонетична транслітерація. */
export function groupCodeVariants(raw: string): string[] {
  const parsed = parseGroupCode(raw);
  if (!parsed) return [];
  const out = new Set<string>();
  const visual = buildGroupCode(parsed.course, replaceLookalikes(parsed.letters), parsed.number);
  if (visual) out.add(visual);
  let phoneticLetters = '';
  for (const ch of parsed.letters) phoneticLetters += LATIN_PHONETIC[ch] ?? ch;
  const phonetic = buildGroupCode(parsed.course, phoneticLetters, parsed.number);
  if (phonetic) out.add(phonetic);
  return [...out];
}

/** Нормалізує вільний текст для порівняння. */
export function normalizeText(raw: string): string {
  const base = raw.normalize('NFC').toLocaleLowerCase('uk').replace(APOSTROPHES_RE, '').replace(/ё/g, 'е');
  return base
    .split(/\s+/)
    .filter(Boolean)
    .map((token) => (isMostlyCyrillic(token) ? replaceLookalikes(token) : token))
    .join(' ');
}

/** Ключ для абревіатур: без крапок, пробілів і дефісів («Укр.мова.» → «укрмова»). */
export function compactKey(raw: string): string {
  return normalizeText(raw).replace(/[\s.\-_,()/]+/g, '');
}

/** Переводить набране в англійській розкладці в українську («rhtywsy» → «кренцін»). */
export function fromEnglishLayout(raw: string): string {
  let out = '';
  for (const ch of raw.toLowerCase()) out += EN_TO_UK_LAYOUT[ch] ?? ch;
  return out;
}

/** Варіанти запиту, з якими варто шукати: як є, як код групи, як інша розкладка. */
export function queryVariants(query: string): string[] {
  const variants = new Set<string>();
  const normalized = normalizeText(query);
  if (normalized) variants.add(normalized);
  const groups = groupCodeVariants(query);
  for (const code of groups) variants.add(normalizeText(code));
  if (normalized && !CYRILLIC_RE.test(normalized) && /[a-z;'[\],.`]/.test(normalized) && groups.length === 0) {
    variants.add(normalizeText(fromEnglishLayout(query)));
  }
  return [...variants];
}

/** Відстань Дамерау–Левенштейна (обмежена), для стійкості до одруківок. */
export function editDistance(a: string, b: string, limit = 3): number {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > limit) return limit + 1;
  const rows = a.length + 1;
  const cols = b.length + 1;
  const d: number[][] = Array.from({ length: rows }, (_, i) => {
    const row = new Array<number>(cols).fill(0);
    row[0] = i;
    return row;
  });
  for (let j = 0; j < cols; j += 1) (d[0] as number[])[j] = j;
  for (let i = 1; i < rows; i += 1) {
    let rowMin = Number.POSITIVE_INFINITY;
    const row = d[i] as number[];
    const prev = d[i - 1] as number[];
    for (let j = 1; j < cols; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let value = Math.min((prev[j] as number) + 1, (row[j - 1] as number) + 1, (prev[j - 1] as number) + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        value = Math.min(value, ((d[i - 2] as number[])[j - 2] as number) + 1);
      }
      row[j] = value;
      rowMin = Math.min(rowMin, value);
    }
    if (rowMin > limit) return limit + 1;
  }
  return (d[rows - 1] as number[])[cols - 1] as number;
}

function scoreVariant(query: string, text: string): number {
  if (!query || !text) return 0;
  const textKey = text.replace(/[\s.\-_,()/]+/g, '');
  const queryKey = query.replace(/[\s.\-_,()/]+/g, '');
  if (textKey === queryKey) return 100;
  if (textKey.startsWith(queryKey)) return 90;
  const words = text.split(/[\s.\-_,()/]+/).filter(Boolean);
  if (words.some((w) => w.startsWith(query))) return 80;
  // «6» знаходить «ауд. 6», але не «16»: числовий запит має збігтися з цілим числом у назві
  if (/^\d+$/.test(query)) return words.includes(query) ? 75 : 0;
  if (textKey.includes(queryKey)) return 60;
  if (queryKey.length >= 4) {
    const limit = queryKey.length >= 7 ? 2 : 1;
    for (const word of words) {
      const head = word.slice(0, queryKey.length);
      if (editDistance(queryKey, head, limit) <= limit) return 40;
    }
  }
  return 0;
}

/**
 * Оцінка збігу 0..100. Перевіряє всі варіанти запиту (код групи, інша розкладка) і всі
 * рядки кандидата (назва, коротка назва, псевдоніми) і бере найкращий результат.
 */
export function matchScore(query: string, ...candidates: Array<string | null | undefined>): number {
  const variants = queryVariants(query);
  if (variants.length === 0) return 0;
  let best = 0;
  for (const candidate of candidates) {
    if (!candidate) continue;
    const text = normalizeText(candidate);
    for (const variant of variants) {
      best = Math.max(best, scoreVariant(variant, text));
      if (best === 100) return best;
    }
  }
  return best;
}

/** Фільтрує й сортує список за найкращим збігом. Порожній запит повертає список як є. */
export function fuzzyFilter<T>(
  items: readonly T[],
  query: string,
  getTexts: (item: T) => Array<string | null | undefined>,
  minScore = 40,
): T[] {
  if (!query.trim()) return [...items];
  return items
    .map((item, index) => ({ item, index, score: matchScore(query, ...getTexts(item)) }))
    .filter((entry) => entry.score >= minScore)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map((entry) => entry.item);
}
