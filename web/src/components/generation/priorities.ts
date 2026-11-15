/**
 * Пріоритети генератора замість сирих ваг: диспетчер вибирає «Дуже важливо / Важливо / Бажано / Не враховувати»,
 * а запит несе відкалібровані ваги (contracts `GenerationWeights`, genfix §1.1).
 *
 * Що тут показуємо. Лише справжні компроміси, які диспетчер може захотіти змінити (рівні дні проти вікон
 * викладачів, стабільність проти якості тощо), кожен одним реченням його словами. Правила, які коледж
 * підтвердив (власник, 15.11.2026: підгрупи разом, без вікон у груп, розвантажена пʼятниця, фізкультура в залі,
 * без днів з однією парою), — не вибір: їх перелічено в `FIXED_RULES` і показано як «Діє завжди», а запит несе
 * для них типові ваги core. Поля запиту не прибираємо (контракт лише розширюється): `windows` після того, як вікна
 * груп стали жорстким правилом, нічого не важить, але запит і далі його несе.
 *
 * Калібрування. Типовий рівень кожного правила дає рівно типову вагу core, тож запуск «як є» = `DEFAULT`.
 * Сусідні рівні здебільшого відрізняються приблизно втричі. Усі пріоритети мʼякі: навіть «Дуже важливо»
 * генератор порушить, якщо інакше не вийде, і покаже це в «Що лишилось».
 */
import { DEFAULT_WEIGHTS } from '@/api/generation';
import { DEFAULT_FRIDAY_MAX_PAIRS } from '@/api/settings-limits';
import type { GenerationWeights } from '@/api/types';

export type Priority = 'required' | 'important' | 'desirable' | 'off';

export const PRIORITIES: readonly Priority[] = ['required', 'important', 'desirable', 'off'];

export const PRIORITY_LABEL: Record<Priority, string> = {
  required: 'Дуже важливо',
  important: 'Важливо',
  desirable: 'Бажано',
  off: 'Не враховувати',
};

/** Що означає рівень — для розкриття «Що означають пріоритети?». */
export const PRIORITY_HINT: Record<Priority, string> = {
  required: 'Найбільша вага. Це все одно не жорстке правило: якщо інакше не виходить, генератор порушить його й покаже де.',
  important: 'Звичайна вага. Приблизно втричі слабша за «Дуже важливо».',
  desirable: 'Поступиться будь-якому важливішому правилу.',
  off: 'Генератор на це не зважає.',
};

export type RuleSection = 'groups' | 'teachers' | 'whole';

export const SECTION_LABEL: Record<RuleSection, string> = {
  groups: 'Для груп',
  teachers: 'Для викладачів',
  whole: 'Для розкладу загалом',
};

/** Ваги для «Дуже важливо», «Важливо», «Бажано» («Не враховувати» — нулі). */
type Ladder = readonly [required: number, important: number, desirable: number];

export interface PriorityRule {
  id: string;
  label: string;
  /** Одне речення словами диспетчера: що генератор робить, коли правило важливе. */
  hint: string;
  section: RuleSection;
  ladder: Partial<Record<keyof GenerationWeights, Ladder>>;
}

/**
 * Правило коледжу, яке генератор дотримується завжди (не пріоритет). Ваги — ті, що запит несе з типовими
 * значеннями core: для підгруп це залишок мʼякого правила (підгрупа без пари в навантаженні), бо саме
 * «підгрупи однієї дисципліни одночасно» — жорстке правило розвʼязувача й ваги не має; вікна груп і пʼятниця —
 * жорсткі правила без ваги (`windows` лишився в запиті для сумісності).
 */
export interface FixedRule {
  id: string;
  /** Коротка назва — для рядка «Пріоритети» старої задачі, якщо її ваги тут були не типові. */
  label: string;
  /** Речення для списку «Діє завжди». */
  text: string;
  weights: ReadonlyArray<keyof GenerationWeights>;
}

/** «3 пар», «1 пари» — після «не більше». */
function pairsAfterAtMost(count: number): string {
  return `${count} ${count % 10 === 1 && count % 100 !== 11 ? 'пари' : 'пар'}`;
}

/** Речення про пʼятницю з числом з налаштувань; без налаштувань — типове значення core. */
export function fridayRuleText(fridayMaxPairs: number | undefined): string {
  return `Пʼятниця розвантажена: у групи не більше ${pairsAfterAtMost(fridayMaxPairs ?? DEFAULT_FRIDAY_MAX_PAIRS)}. Число змінюють у налаштуваннях.`;
}

export const FIXED_RULES: readonly FixedRule[] = [
  {
    id: 'groupWindows',
    label: 'Без вікон у груп',
    // жорстке правило (власник, 15.11.2026: «краще викладачам вікна»); вікна викладачів — пріоритет нижче
    text: 'Група не чекає між парами: якщо без вікна не обійтися, воно буде у викладача.',
    weights: ['windows'],
  },
  {
    id: 'friday',
    label: 'Розвантажена пʼятниця',
    // жорстке правило без ваги; число — налаштування «Максимум пар у пʼятницю», форма підставляє його сама
    text: fridayRuleText(undefined),
    weights: [],
  },
  {
    id: 'subgroups',
    label: 'Підгрупи разом',
    // одночасність — жорстке правило; «пара за парою» для одного викладача — мʼяке (вага 30), тому «де це вдається»
    text: 'Підгрупи однієї дисципліни займаються одночасно. Якщо так не можна, бо обидві веде один викладач, генератор ставить їх пара за парою, де це вдається.',
    weights: ['subgroupsTogether'],
  },
  {
    id: 'specialRooms',
    label: 'Фізкультура в спортзалі',
    text: 'Фізкультура — у спортзалі чи на спортмайданчику. Якщо зал заповнений, пара піде в клас, і це буде видно в результаті.',
    weights: ['specialRooms'],
  },
  {
    id: 'onePairDays',
    label: 'Без днів з однією парою',
    text: 'Група не приходить заради однієї пари, якщо цього можна уникнути.',
    weights: ['studentOnePairDay'],
  },
];

/** Підказка «4-та пара зміни»: одна на генератор і редактор розкладу. */
export const LATE_PAIR_HINT =
  'Уникати 4-ї пари зміни (у 2-й зміні — 8-ї); коледж вважає її звичайною, тому типово вимкнено.';

export const PRIORITY_RULES: readonly PriorityRule[] = [
  {
    id: 'brokenDays',
    label: 'Рвані дні',
    hint: 'Щоб у чисельник і знаменник день групи мав стільки ж пар і починався з тієї ж пари.',
    section: 'groups',
    ladder: { brokenDays: [30, 10, 3] },
  },
  {
    id: 'disciplineTwice',
    label: 'Дисципліна двічі на день',
    hint: 'Не ставити групі ту саму дисципліну двічі за день.',
    section: 'groups',
    ladder: { disciplineTwice: [30, 10, 3] },
  },
  {
    id: 'latePairs',
    label: '4-та пара зміни',
    hint: LATE_PAIR_HINT,
    section: 'groups',
    ladder: { latePairs: [27, 9, 3] },
  },
  {
    id: 'teacherWindows',
    label: 'Вікна викладачів',
    hint: 'Не залишати викладачу порожніх пар між заняттями; очікування між 1-ю і 2-ю зміною важить утричі менше.',
    section: 'teachers',
    ladder: { teacherWindows: [30, 10, 3], teacherShiftWait: [9, 3, 1] },
  },
  {
    id: 'teacherDays',
    label: 'Дні викладачів',
    hint: 'Не кликати викладача заради однієї пари, не додавати йому робочих днів і не ставити більше пар на день, ніж у налаштуваннях.',
    section: 'teachers',
    ladder: { teacherDays: [15, 5, 2], teacherOverload: [9, 3, 1] },
  },
  {
    id: 'preferences',
    label: 'Побажання викладачів',
    hint: 'Не ставити пари в час, який викладач позначив як небажаний.',
    section: 'teachers',
    ladder: { preferences: [15, 5, 2] },
  },
  {
    id: 'homeRoom',
    label: 'Аудиторія викладача',
    hint: 'Ставити пару в аудиторію, де викладач зазвичай веде цю дисципліну.',
    section: 'teachers',
    ladder: { homeRoom: [9, 3, 1] },
  },
  {
    id: 'stability',
    label: 'Не переставляти без потреби',
    hint: 'Залишати пари там, де вони стоять у базовій версії, і в тих самих аудиторіях; без базової версії не діє.',
    section: 'whole',
    // «Дуже важливо»: перенесення цілої пари (2 тижні × 30) коштує як два вікна групи за типових ваг — рухаємо
    // лише заради конфліктів; крок тут більший за ×3 (×6 і ×5), бо типова вага 1 уже найменша
    ladder: { stability: [30, 5, 1] },
  },
];

function ladderValue(ladder: Ladder, priority: Priority): number {
  if (priority === 'off') return 0;
  return ladder[priority === 'required' ? 0 : priority === 'important' ? 1 : 2];
}

/** Рівень правила для набору ваг; null — ваги не відповідають жодному рівню (задано вручну). */
export function priorityOf(rule: PriorityRule, weights: GenerationWeights): Priority | null {
  const entries = Object.entries(rule.ladder) as Array<[keyof GenerationWeights, Ladder]>;
  return PRIORITIES.find((priority) => entries.every(([key, ladder]) => weights[key] === ladderValue(ladder, priority))) ?? null;
}

/** Ваги з новим рівнем правила; решта ваг не змінюється. */
export function withPriority(weights: GenerationWeights, rule: PriorityRule, priority: Priority): GenerationWeights {
  const next = { ...weights };
  for (const [key, ladder] of Object.entries(rule.ladder) as Array<[keyof GenerationWeights, Ladder]>) {
    next[key] = ladderValue(ladder, priority);
  }
  return next;
}

/** Ваги, у яких правила «Діє завжди» мають типові значення core (стара задача могла нести інші). */
export function withFixedRules(weights: GenerationWeights): GenerationWeights {
  const next = { ...weights };
  for (const rule of FIXED_RULES) {
    for (const key of rule.weights) next[key] = DEFAULT_WEIGHTS[key];
  }
  return next;
}

/** Типовий рівень правила — той, що дає типові ваги core. */
export function defaultPriority(rule: PriorityRule): Priority {
  return priorityOf(rule, { ...DEFAULT_WEIGHTS }) ?? 'important';
}

/** Ваги правила одним рядком, коли вони не збігаються з рівнем: «вага 50» або «ваги 3, 7, 1». */
export function customWeightText(rule: Pick<PriorityRule, 'ladder'>, weights: GenerationWeights): string {
  const values = (Object.keys(rule.ladder) as Array<keyof GenerationWeights>).map((key) => weights[key]);
  return values.length === 1 ? `вага ${values[0] ?? 0}` : `ваги ${values.join(', ')}`;
}

/**
 * Що в задачі не типове: пріоритети з іншим рівнем («Рвані дні — Бажано») і, для старих задач, правила
 * «Діє завжди» з нетиповою вагою («Підгрупи разом — вага 30», «Без вікон у груп — вага 10»). Порожньо — усе типове.
 */
export function changedPriorities(weights: GenerationWeights): Array<{ rule: { id: string; label: string }; text: string }> {
  const priorities = PRIORITY_RULES.flatMap((rule) => {
    const priority = priorityOf(rule, weights);
    if (priority === defaultPriority(rule)) return [];
    return [{ rule, text: priority ? PRIORITY_LABEL[priority] : customWeightText(rule, weights) }];
  });
  const fixed = FIXED_RULES.flatMap((rule) => {
    const values = rule.weights.map((key) => weights[key]);
    if (rule.weights.every((key) => weights[key] === DEFAULT_WEIGHTS[key])) return [];
    return [{ rule, text: values.length === 1 ? `вага ${values[0] ?? 0}` : `ваги ${values.join(', ')}` }];
  });
  return [...priorities, ...fixed];
}
