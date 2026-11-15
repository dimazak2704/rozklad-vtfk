/**
 * Пріоритети мʼяких правил генератора замість сирих ваг: диспетчер вибирає «Дуже важливо / Важливо /
 * Бажано / Не враховувати», а запит несе відкалібровані ваги (contracts `GenerationWeights`, genfix §1.1).
 * Найвищий рівень навмисно не «Обовʼязково»: жодне з цих правил не жорстке (genfix repair).
 *
 * Калібрування. Типовий рівень кожного правила дає рівно типову вагу core, тож запуск «як є» = `DEFAULT`.
 * Сусідні рівні здебільшого відрізняються приблизно втричі: «Дуже важливо» перебиває будь-яке «Важливо» з
 * типових, «Бажано» поступається йому. Усі правила мʼякі: навіть «Дуже важливо» генератор порушить, якщо
 * інакше не вийде, і покаже це в «Що лишилось». Статистика в підказках — з чинного розкладу коледжу
 * (seed/schedule-2025-26.xlsx, нічні дослідження genRun/genModel, метрики опублікованої версії на стенді).
 */
import { DEFAULT_WEIGHTS } from '@/api/generation';
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
  off: 'Вага 0: генератор на це не зважає.',
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
  /** Одне чесне речення: що штрафується і, де є, як це в чинному розкладі. */
  hint: string;
  section: RuleSection;
  ladder: Partial<Record<keyof GenerationWeights, Ladder>>;
}

/** Підказка «4-та пара зміни» (genfix §4): одна на генератор і редактор розкладу. */
export const LATE_PAIR_HINT =
  'Штраф за 4-ту пару зміни (у 1-й зміні — 4 пара, у 2-й — 8 пара; у пʼятницю 2-га зміна має лише 3 пари). ' +
  'Це не правило коледжу: у чинному розкладі 4-та пара зміни є в 57 % днів груп. Вага лише рівніше розкладає пари по тижню.';

export const PRIORITY_RULES: readonly PriorityRule[] = [
  {
    id: 'groupWindows',
    label: 'Вікна груп',
    hint: 'Порожня пара між заняттями групи в один день; у чинному розкладі коледжу таких немає жодної.',
    section: 'groups',
    ladder: { windows: [30, 10, 3] },
  },
  {
    id: 'brokenDays',
    label: 'Рвані дні',
    hint: 'День, коли в чисельник і знаменник у групи різна кількість пар або різна перша пара; у чинному розкладі таких 34.',
    section: 'groups',
    ladder: { brokenDays: [30, 10, 3] },
  },
  {
    id: 'subgroupsTogether',
    label: 'Підгрупи разом',
    hint: 'Одна підгрупа на парі, а інша без заняття, або підгрупи одного викладача не підряд; у чинному розкладі 72 з 75 слотів підгруп зайняті обома.',
    section: 'groups',
    ladder: { subgroupsTogether: [30, 10, 3] },
  },
  {
    id: 'latePairs',
    label: '4-та пара зміни',
    hint: 'Пара на 4-му місці зміни (12:10 у 1-й зміні); у чинному розкладі вона є в 57 % днів груп, тож це не правило коледжу, а лише спосіб рівніше розкласти пари.',
    section: 'groups',
    ladder: { latePairs: [27, 9, 3] },
  },
  {
    id: 'disciplineTwice',
    label: 'Дисципліна двічі на день',
    hint: 'Та сама дисципліна двічі за день у групи; у чинному розкладі так у 12 з 560 днів груп, тричі — ніколи.',
    section: 'groups',
    ladder: { disciplineTwice: [30, 10, 3] },
  },
  {
    id: 'teacherWindows',
    label: 'Вікна викладачів',
    hint: 'Порожня пара між заняттями викладача в межах однієї зміни; у чинному розкладі їх 117 (без очікувань між змінами), тож коледж їх терпить.',
    section: 'teachers',
    ladder: { teacherWindows: [30, 10, 3] },
  },
  {
    id: 'teacherShiftWait',
    label: 'Очікування між змінами',
    hint: 'Викладач веде пару в 1-й зміні й чекає на пару в 2-й; у чинному розкладі 154 з 781 робочого дня викладачів охоплюють обидві зміни. Типово важить утричі менше за вікно викладача: без цього правила генератор перетворює вікна на очікування між змінами.',
    section: 'teachers',
    ladder: { teacherShiftWait: [9, 3, 1] },
  },
  {
    id: 'preferences',
    label: 'Побажання викладачів',
    hint: 'Пара в слоті, який викладач позначив як небажаний.',
    section: 'teachers',
    ladder: { preferences: [15, 5, 2] },
  },
  {
    id: 'homeRoom',
    label: 'Аудиторія викладача',
    hint: 'Пара не в аудиторії, де викладач зазвичай веде цю дисципліну.',
    section: 'teachers',
    ladder: { homeRoom: [9, 3, 1] },
  },
  {
    id: 'dailyBalance',
    label: 'Рівномірні дні',
    hint: 'День з однією парою в групи чи викладача, зайвий робочий день викладача або більше пар на день, ніж дозволяють налаштування.',
    section: 'whole',
    // день з однією парою в групи (3 з 560 у чинному), дні викладачів, перевантаження викладача
    ladder: { studentOnePairDay: [30, 10, 3], teacherDays: [15, 5, 2], teacherOverload: [9, 3, 1] },
  },
  {
    id: 'stability',
    label: 'Не переставляти без потреби',
    hint: 'Пара стоїть не там, де в базовій версії, або в іншій аудиторії; без базової версії не діє.',
    section: 'whole',
    // «Дуже важливо»: перенесення цілої пари (2 тижні × 30) коштує як два вікна групи за типових ваг — рухаємо
    // лише заради конфліктів; крок тут більший за ×3 (×6 і ×5), бо типова вага 1 уже найменша
    ladder: { stability: [30, 5, 1] },
  },
  {
    id: 'specialRooms',
    label: 'Спортзал — як у базовій версії',
    hint: 'Пара предмета, який у базовій версії стояв лише в спортзалі чи на спортмайданчику, в іншій аудиторії. У чинному розкладі так стоять усі 60 предметів фізкультури, жодна пара — не в класі. Без базової версії не діє.',
    section: 'whole',
    // не жорстке правило (питання до коледжу): коли зал зайнятий чи закритий, пара піде в клас за цю ціну
    ladder: { specialRooms: [30, 10, 3] },
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

/** Типовий рівень правила — той, що дає типові ваги core. */
export function defaultPriority(rule: PriorityRule): Priority {
  return priorityOf(rule, { ...DEFAULT_WEIGHTS }) ?? 'important';
}

/** Ваги правила одним рядком, коли вони не збігаються з рівнем: «вага 50» або «ваги 3, 7, 1». */
export function customWeightText(rule: PriorityRule, weights: GenerationWeights): string {
  const values = (Object.keys(rule.ladder) as Array<keyof GenerationWeights>).map((key) => weights[key]);
  return values.length === 1 ? `вага ${values[0] ?? 0}` : `ваги ${values.join(', ')}`;
}

/** Правила з нетиповим рівнем: «Вікна груп — Бажано». Порожньо — усе типове. */
export function changedPriorities(weights: GenerationWeights): Array<{ rule: PriorityRule; text: string }> {
  return PRIORITY_RULES.flatMap((rule) => {
    const priority = priorityOf(rule, weights);
    if (priority === defaultPriority(rule)) return [];
    return [{ rule, text: priority ? PRIORITY_LABEL[priority] : customWeightText(rule, weights) }];
  });
}
