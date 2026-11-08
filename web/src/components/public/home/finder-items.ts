/**
 * Пошук на головній: локальні довідники (групи, викладачі, аудиторії) → миттєві результати без запиту
 * на кожну літеру. Нормалізація й нечіткий збіг — ті самі, що в сервері (lib/search, DOMAIN §10).
 */
import type { EntityType, PublicGroupDto, PublicRoomDto, PublicTeacherDto, SearchResultDto } from '@/api/types';
import { paths } from '@/app/routes';
import { capitalize, ROOM_TYPE_LABEL } from '@/lib/labels';
import { groupSpecialtyName } from '@/lib/names';
import { roomLabel } from '@/lib/schedule';
import { matchScore } from '@/lib/search';

export interface FinderItem {
  key: string;
  type: EntityType;
  id: string;
  label: string;
  sublabel: string | null;
  url: string;
  /** Рядки, за якими шукаємо. */
  texts: string[];
}

const TYPE_ORDER: Record<EntityType, number> = { GROUP: 0, TEACHER: 1, ROOM: 2 };
const UK = new Intl.Collator('uk');

export function groupItems(groups: readonly PublicGroupDto[]): FinderItem[] {
  return groups.map((group) => {
    const specialty = groupSpecialtyName(group);
    return {
      key: `GROUP:${group.id}`,
      type: 'GROUP',
      id: group.id,
      label: group.code,
      sublabel: [`${group.course} курс`, specialty].filter(Boolean).join(' · '),
      url: paths.group(group.code),
      texts: [group.code],
    };
  });
}

export function teacherItems(teachers: readonly PublicTeacherDto[]): FinderItem[] {
  return teachers.map((teacher) => ({
    key: `TEACHER:${teacher.id}`,
    type: 'TEACHER',
    id: teacher.id,
    label: teacher.shortName,
    sublabel: teacher.cycleCommission,
    url: paths.teacher(teacher.id),
    texts: [teacher.shortName, teacher.displayName],
  }));
}

export function roomItems(rooms: readonly PublicRoomDto[]): FinderItem[] {
  return rooms.map((room) => ({
    key: `ROOM:${room.id}`,
    type: 'ROOM',
    id: room.id,
    label: roomLabel(room.name) ?? room.name,
    sublabel: [capitalize(ROOM_TYPE_LABEL[room.type]), room.building].filter(Boolean).join(' · '),
    url: paths.room(room.id),
    texts: [room.name, `ауд. ${room.name}`, ROOM_TYPE_LABEL[room.type]],
  }));
}

/** Результат серверного пошуку — коли довідники не завантажилися. */
export function fromSearchResult(result: SearchResultDto): FinderItem {
  return {
    key: `${result.type}:${result.id}`,
    type: result.type,
    id: result.id,
    label: result.label,
    sublabel: result.sublabel,
    url: result.url,
    texts: [result.label],
  };
}

/**
 * Найкращі збіги: за оцінкою, далі групи → викладачі → аудиторії, далі за абеткою.
 * «4ki1» → 4КІ1, «кренц» → Кренцін, «rhtywsy» (англ. розкладка) → Кренцін, «6» → ауд. 6 (не 16).
 */
export function findItems(items: readonly FinderItem[], query: string, limit = 9): FinderItem[] {
  if (!query.trim()) return [];
  return items
    .map((item) => ({ item, score: matchScore(query, ...item.texts) }))
    .filter((entry) => entry.score >= 40)
    .sort(
      (a, b) =>
        b.score - a.score || TYPE_ORDER[a.item.type] - TYPE_ORDER[b.item.type] || UK.compare(a.item.label, b.item.label),
    )
    .slice(0, limit)
    .map((entry) => entry.item);
}
