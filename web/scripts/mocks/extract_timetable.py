"""
Витягує базовий розклад із seed/schedule-2025-26.xlsx (аркуш «Розклад друк») для мок-API SPA
і записує типізований модуль web/src/mocks/fixtures/timetable.data.ts.

Правила читання — docs/DOMAIN.md §11 (два рядки на пару, обʼєднані клітинки, підгрупи) і §10
(нормалізація абревіатур і відомі одруківки). Невідомі абревіатури лишаються як є.

Запуск (з кореня репозиторію):
    python -I web/scripts/mocks/extract_timetable.py seed/schedule-2025-26.xlsx web/src/mocks/fixtures/timetable.data.ts
Сумнівні клітинки друкуються в stderr.
"""
from __future__ import annotations

import io
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass, field

import openpyxl

SHEET = 'Розклад друк'
WEEKDAYS = {'ПОНЕДІЛОК': 1, 'ВІВТОРОК': 2, 'СЕРЕДА': 3, 'ЧЕТВЕР': 4, "П'ЯТНИЦЯ": 5}
APOSTROPHE = "'"

# DOMAIN §10: «Укр.мова.»=«Укр.мова», «Фк»=«ФК», «Вс.Іст.»=«Вс.іст.», «Вст.спц»=«Вст.спц.» збігаються за
# компактним ключем; решту відомих синонімів задаємо явно (компактний ключ → канонічна абревіатура)
DISCIPLINE_ALIASES = {
    'вс': 'Вс.іст.',  # обрізане «Вс.»
    'всспц': 'Вст.спц.',  # «Вс.спц.» — ймовірно той самий «Вступ до спеціальності»
    'інфом': 'Інф.',
    'ндекс': 'НДЕКСМ',  # у таблиці 4 курсу назва обрізана
    'ндкс': 'НДЕКСМ',
    # очевидні одруківки таблиці (той самий викладач веде і «правильний» варіант)
    'вміст': 'Вс.іст.',
    'матет': 'Матем.',
    'маиспц': 'Мат.спц.',
    'екол': 'Еколог.',
    'тргеог': 'Тр.геогр.',
    'статист': 'Стат.',
    'схем': 'Схемот.',
}
# Канонічне написання, коли в таблиці кілька варіантів однієї абревіатури
DISCIPLINE_CANONICAL = {
    'укрмова': 'Укр.мова',
    'фк': 'ФК',
    'всіст': 'Вс.іст.',
    'встспц': 'Вст.спц.',
    'істукр': 'Іст.Укр.',
    'ндексм': 'НДЕКСМ',
    'інф': 'Інф.',
    'укрмов': 'Укр.мов.',
}
# DOMAIN §10: реальні одруківки в прізвищах
TEACHER_TYPOS = {
    'Граболінська': 'Гарболінська',
    'Герасменко': 'Герасименко',
    'Хорченко': 'Харченко',
    # ще дві одруківки з таблиці: «Вернигорв» (1 раз проти 7 «Вернигора») і куратор «Шидлорвська Т.І.»
    'Вернигорв': 'Вернигора',
    'Шидлорвська': 'Шидловська',
}
# «Матем.Овчар Ін.», «Укр.мов.Зоріна» — у клітинку дисципліни потрапило прізвище викладача
GLUED_TEACHER_RE = re.compile('^(.*\\.)\\s*([А-ЯІЇЄҐ][а-яіїєґ' + "'" + ']{2,}(?:\\s.*)?)$')
ROOM_ALIASES = {'с.з': 'с.з.', 'с.з.': 'с.з.', 'с.п': 'с.п.', 'с.п.': 'с.п.'}
GROUP_CODE_RE = re.compile(r'^([0-9І])([А-ЯІЇЄҐ]{1,3})(\d?)$')
GLUED_INITIAL_RE = re.compile('([а-яіїєґ' + APOSTROPHE + r'])([А-ЯІЇЄҐ][а-яіїєґ]?\.)')

warnings: list[str] = []


def warn(message: str) -> None:
    warnings.append(message)


def clean(value) -> str | None:
    if value is None:
        return None
    text = unicodedata.normalize('NFC', str(value)).replace(' ', ' ').strip()
    text = re.sub(r'\s+', ' ', text)
    return text or None


def unify_apostrophes(text: str) -> str:
    return re.sub('[ʼ’`]', APOSTROPHE, text)


def compact_key(text: str) -> str:
    return re.sub(r'[\s.\-_,()/]+', '', unify_apostrophes(text).lower())


def canonical_group(raw: str) -> str | None:
    compact = re.sub(r'\s+', '', raw.upper())
    match = GROUP_CODE_RE.match(compact)
    if not match:
        return None
    course, letters, number = match.groups()
    if course == 'І':  # кирилична «І» замість одиниці: «ІКІ1» → «1КІ1»
        course = '1'
    return f'{course}{letters.replace("И", "І")}{number}'


def normalize_room(raw: str | None) -> str | None:
    if raw is None:
        return None
    text = raw.strip()
    if text.lower().startswith('с.'):
        text = text.lower()
    return ROOM_ALIASES.get(text, text)


@dataclass
class TeacherToken:
    surname: str
    initials: tuple[str, ...]

    @property
    def text(self) -> str:
        return self.surname + (' ' + ''.join(f'{i}.' for i in self.initials) if self.initials else '')


def parse_teacher(raw: str) -> tuple[TeacherToken, bool]:
    """«Харченко О.(п)» → (Харченко О., одна підгрупа); «ХарченкоГ.» → Харченко Г."""
    text = unify_apostrophes(raw)
    one_subgroup = bool(re.search(r'\(п\.?\)', text))
    text = re.sub(r'\(п\.?\)', '', text)
    # «Граболінська гр.», «Можаровська (гр.)», «Крисак Л .(гр).» — позначка «уся група»
    text = re.sub(r'\(\s*гр\s*\.?\s*\)\.?', '', text)
    text = re.sub(r'\s+гр\.?$', '', text.strip())
    text = re.sub(r'\s+\.', '.', text)
    text = GLUED_INITIAL_RE.sub(r'\1 \2', text)
    parts = text.strip().split(' ', 1)
    surname = TEACHER_TYPOS.get(parts[0], parts[0])
    initials: tuple[str, ...] = ()
    if len(parts) > 1:
        initials = tuple(p for p in re.split(r'[.\s]+', parts[1]) if p)
    return TeacherToken(surname, initials), one_subgroup


def initials_compatible(a: tuple[str, ...], b: tuple[str, ...]) -> bool:
    return all(x.startswith(y) or y.startswith(x) for x, y in zip(a, b))


@dataclass
class Lesson:
    group: str
    weekday: int
    pair: int
    parity: str  # B | N | D
    subgroup: int
    discipline: str
    teacher: TeacherToken | None
    room: str | None


@dataclass
class Group:
    code: str
    course: int
    home_room: str | None
    curator: TeacherToken | None
    column: int


@dataclass
class Sheet:
    ws: object
    merged: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        for rng in self.ws.merged_cells.ranges:
            for r in range(rng.min_row, rng.max_row + 1):
                for c in range(rng.min_col, rng.max_col + 1):
                    self.merged[(r, c)] = (rng.min_row, rng.min_col, rng.max_row, rng.max_col)

    def value(self, r: int, c: int) -> str | None:
        m = self.merged.get((r, c))
        if m:
            return clean(self.ws.cell(m[0], m[1]).value)
        return clean(self.ws.cell(r, c).value)

    def span(self, r: int, c: int):
        return self.merged.get((r, c))


def group_codes_in_row(sheet: Sheet, r: int) -> list[tuple[int, str]]:
    codes = []
    for c in range(3, sheet.ws.max_column + 1, 3):
        raw = clean(sheet.ws.cell(r, c).value)
        code = canonical_group(raw) if raw else None
        if code:
            codes.append((c, code))
    return codes


def find_blocks(sheet: Sheet) -> list[tuple[int, int]]:
    """Блок курсу починається з рядка кодів груп (заголовок «N курс» є не в кожному блоці)."""
    blocks = []
    for r in range(1, sheet.ws.max_row + 1):
        codes = group_codes_in_row(sheet, r)
        courses = {code[0] for _, code in codes}
        if len(codes) >= 5 and len(courses) == 1:
            blocks.append((r, int(courses.pop())))
    return blocks


def read_groups(sheet: Sheet, header: int) -> list[Group]:
    groups = []
    for c, code in group_codes_in_row(sheet, header):
        curator_raw = clean(sheet.ws.cell(header - 1, c).value)
        curator = None
        if curator_raw:
            curator_raw = re.sub(r'\(.*\)', '', curator_raw).strip()  # «Шароварська С.П. (Погосян Т.В.)»
            curator, _ = parse_teacher(curator_raw)
        home_room = normalize_room(clean(sheet.ws.cell(header, c + 2).value))
        groups.append(Group(code, int(code[0]), home_room, curator, c))
    return groups


def split_slash(text: str | None) -> list[str | None]:
    if text is None:
        return [None]
    return [p.strip() or None for p in text.split('/')]


def lessons_for_pair(sheet: Sheet, group: Group, weekday: int, pair: int, r1: int) -> list[Lesson]:
    cd, ct, cr = group.column, group.column + 1, group.column + 2
    r2 = r1 + 1
    disc_span = sheet.span(r1, cd)

    def own(r: int, c: int) -> str | None:
        # клітинка, що входить в одне обʼєднання з дисципліною, — не окреме значення викладача/аудиторії
        span = sheet.span(r, c)
        if span and disc_span and span == disc_span:
            return None
        return sheet.value(r, c)

    if disc_span and disc_span[3] > disc_span[1]:
        warn(f'{group.code} д{weekday} п{pair}: дисципліна обʼєднана по горизонталі {disc_span}')
    if disc_span and disc_span[2] > r2:
        warn(f'{group.code} д{weekday} п{pair}: дисципліна обʼєднана на кілька пар {disc_span}')

    d_top, d_bot = sheet.value(r1, cd), sheet.value(r2, cd)
    d_both = bool(disc_span and disc_span[0] <= r1 and disc_span[2] >= r2) or (d_top is not None and d_top == d_bot)
    t_top, t_bot = own(r1, ct), own(r2, ct)
    room_top, room_bot = own(r1, cr), own(r2, cr)

    out: list[Lesson] = []

    def emit(parity: str, discipline: str, teacher_raw: str | None, room_raw: str | None, subgroup: int = 0) -> None:
        teachers = split_slash(teacher_raw)
        rooms = split_slash(room_raw)
        if len(teachers) > 1 or len(rooms) > 1:
            # «Крисак Л./Можаровська» + «110/12» в одному рядку — дві підгрупи
            for index in range(max(len(teachers), len(rooms))):
                t = teachers[index] if index < len(teachers) else teachers[-1]
                rm = rooms[index] if index < len(rooms) else rooms[-1]
                emit(parity, discipline, t, rm, index + 1)
            return
        token = None
        if teacher_raw:
            token, one_subgroup = parse_teacher(teacher_raw)
            if one_subgroup and subgroup == 0:
                subgroup = 1
        out.append(Lesson(group.code, weekday, pair, parity, subgroup, discipline, token, normalize_room(room_raw)))

    if d_both and d_top:
        same_teacher = t_bot is None or t_top == t_bot
        same_room = room_bot is None or room_top == room_bot
        if same_teacher and same_room:
            emit('B', d_top, t_top, room_top)
        else:
            # дисципліна на обидва рядки, а викладачі чи аудиторії різні — дві підгрупи
            emit('B', d_top, t_top, room_top, 1)
            emit('B', d_top, t_bot or t_top, room_bot or room_top, 2)
        return out
    if d_top:
        emit('N', d_top, t_top, room_top)
    elif t_top or room_top:
        warn(f'{group.code} д{weekday} п{pair}: верхній рядок без дисципліни ({t_top}, {room_top}) — пропущено')
    if d_bot:
        emit('D', d_bot, t_bot, room_bot)
    elif t_bot or room_bot:
        warn(f'{group.code} д{weekday} п{pair}: нижній рядок без дисципліни ({t_bot}, {room_bot}) — пропущено')
    return out


def read_block(sheet: Sheet, header: int, end: int) -> tuple[list[Group], list[Lesson]]:
    groups = read_groups(sheet, header)
    lessons: list[Lesson] = []
    r = header + 1
    while r < end:
        span = sheet.span(r, 1)
        is_anchor = not span or (span[0], span[1]) == (r, 1)
        day_text = clean(sheet.ws.cell(r, 1).value) if is_anchor else None
        weekday = WEEKDAYS.get(unify_apostrophes(day_text or ''))
        if not weekday:
            r += 1
            continue
        day_end = span[2] if span else r + 7
        row = r
        while row <= day_end:
            pair_text = clean(sheet.ws.cell(row, 2).value)
            match = re.match(r'^(\d+)', pair_text or '')
            if match:
                pair = int(match.group(1))
                for group in groups:
                    lessons.extend(lessons_for_pair(sheet, group, weekday, pair, row))
                row += 2
            else:
                row += 1
        r = day_end + 1
    return groups, lessons


def resolve_teachers(groups: list[Group], lessons: list[Lesson]) -> None:
    """Звʼязує «Вихристюк» і куратора «Вихристюк Ю.А.»; несумісні ініціали — різні люди (DOMAIN §10)."""
    tokens = [g.curator for g in groups if g.curator] + [l.teacher for l in lessons if l.teacher]
    people: dict[str, list[tuple[str, ...]]] = defaultdict(list)
    for token in tokens:
        if not token.initials:
            continue
        known = people[token.surname]
        for index, initials in enumerate(known):
            if initials_compatible(initials, token.initials):
                richer = (len(token.initials), sum(map(len, token.initials))) > (len(initials), sum(map(len, initials)))
                if richer:
                    known[index] = token.initials
                break
        else:
            known.append(token.initials)

    # хто яку дисципліну веде — щоб розвʼязати прізвище без ініціалів, коли однофамільців кілька
    teaches: dict[tuple[str, tuple[str, ...]], set[str]] = defaultdict(set)
    for lesson in lessons:
        if lesson.teacher and lesson.teacher.initials:
            for initials in people[lesson.teacher.surname]:
                if initials_compatible(initials, lesson.teacher.initials):
                    teaches[(lesson.teacher.surname, initials)].add(compact_key(lesson.discipline))

    def resolve(token: TeacherToken, discipline: str | None) -> TeacherToken:
        candidates = people.get(token.surname, [])
        if token.initials:
            for initials in candidates:
                if initials_compatible(initials, token.initials):
                    return TeacherToken(token.surname, initials)
            return token
        if len(candidates) == 1:
            return TeacherToken(token.surname, candidates[0])
        if len(candidates) > 1 and discipline:
            matching = [i for i in candidates if compact_key(discipline) in teaches[(token.surname, i)]]
            if len(matching) == 1:
                return TeacherToken(token.surname, matching[0])
            warn(f'Неоднозначне прізвище «{token.surname}» ({discipline}): {candidates}')
        return token

    for group in groups:
        if group.curator:
            group.curator = resolve(group.curator, None)
    for lesson in lessons:
        if lesson.teacher:
            lesson.teacher = resolve(lesson.teacher, lesson.discipline)


def split_glued_teachers(lessons: list[Lesson]) -> None:
    """«Матем.Овчар Ін.» → «Матем.», якщо хвіст — прізвище відомого викладача (а не «Іст.Укр»)."""
    surnames = {l.teacher.surname for l in lessons if l.teacher}
    for lesson in lessons:
        glued = GLUED_TEACHER_RE.match(lesson.discipline)
        if not glued:
            continue
        token, _ = parse_teacher(glued.group(2))
        if token.surname not in surnames:
            continue
        warn(f'{lesson.group} д{lesson.weekday} п{lesson.pair}: прізвище в клітинці дисципліни «{lesson.discipline}»')
        lesson.discipline = glued.group(1)
        lesson.teacher = lesson.teacher or token


def canonical_disciplines(lessons: list[Lesson]) -> None:
    def key_of(name: str) -> tuple[str, str | None]:
        key = compact_key(name)
        alias = DISCIPLINE_ALIASES.get(key)
        return (compact_key(alias), alias) if alias else (key, None)

    variants: dict[str, Counter] = defaultdict(Counter)
    for lesson in lessons:
        key, alias = key_of(lesson.discipline)
        variants[key][alias or lesson.discipline] += 1
    chosen = {key: DISCIPLINE_CANONICAL.get(key) or counter.most_common(1)[0][0] for key, counter in variants.items()}
    for lesson in lessons:
        lesson.discipline = chosen[key_of(lesson.discipline)[0]]


def ts_str(value: str | None) -> str:
    if value is None:
        return 'null'
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"


def person(token: TeacherToken | None) -> str | None:
    # у користувацькому тексті — український апостроф (U+02BC), як в інтерфейсі
    return token.text.replace(APOSTROPHE, 'ʼ') if token else None


def write_ts(path: str, groups: list[Group], lessons: list[Lesson]) -> None:
    lines = [
        '/**',
        ' * ЗГЕНЕРОВАНО з seed/schedule-2025-26.xlsx (аркуш «Розклад друк», I семестр 2025/26) скриптом',
        ' * web/scripts/mocks/extract_timetable.py. Не редагуйте вручну — перезапустіть скрипт.',
        ' * Абревіатури й прізвища — як у таблиці, з нормалізацією DOMAIN §10 (одруківки, синоніми).',
        ' */',
        "import type { RawGroup, RawLesson } from './timetable.types';",
        '',
        '/** Групи в порядку таблиці: код, курс, домашня аудиторія, куратор. */',
        'export const RAW_GROUPS: readonly RawGroup[] = [',
    ]
    for g in groups:
        curator = person(g.curator)
        lines.append(f'  [{ts_str(g.code)}, {g.course}, {ts_str(g.home_room)}, {ts_str(curator)}],')
    lines += [
        '];',
        '',
        '/** Заняття шаблону: група, день (1–5), пара, парність (B/N/D), підгрупа, дисципліна, викладач, аудиторія. */',
        'export const RAW_LESSONS: readonly RawLesson[] = [',
    ]
    for l in lessons:
        teacher = person(l.teacher)
        lines.append(
            f'  [{ts_str(l.group)}, {l.weekday}, {l.pair}, {ts_str(l.parity)}, {l.subgroup}, '
            f'{ts_str(l.discipline)}, {ts_str(teacher)}, {ts_str(l.room)}],'
        )
    lines += ['];', '']
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(lines))


def main() -> None:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')
    source, target = sys.argv[1], sys.argv[2]
    sheet = Sheet(openpyxl.load_workbook(source)[SHEET])
    blocks = find_blocks(sheet)
    groups: list[Group] = []
    lessons: list[Lesson] = []
    for index, (header, _course) in enumerate(blocks):
        # наступний блок починається рядком кураторів над своїми кодами груп
        end = blocks[index + 1][0] - 1 if index + 1 < len(blocks) else sheet.ws.max_row + 1
        block_groups, block_lessons = read_block(sheet, header, end)
        groups += block_groups
        lessons += block_lessons
    split_glued_teachers(lessons)
    canonical_disciplines(lessons)
    resolve_teachers(groups, lessons)
    order = {g.code: i for i, g in enumerate(groups)}
    lessons.sort(key=lambda l: (order[l.group], l.weekday, l.pair, l.subgroup, 'BND'.index(l.parity)))
    write_ts(target, groups, lessons)
    teachers = {l.teacher.text for l in lessons if l.teacher} | {g.curator.text for g in groups if g.curator}
    print(
        f'груп: {len(groups)}, занять: {len(lessons)}, викладачів: {len(teachers)}, '
        f'дисциплін: {len({l.discipline for l in lessons})}, аудиторій: {len({l.room for l in lessons if l.room})}'
    )
    for message in warnings:
        print('УВАГА:', message, file=sys.stderr)


if __name__ == '__main__':
    main()
