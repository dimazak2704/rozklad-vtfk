/**
 * Команди відсутностей і змін: перевірка запиту як у core (400 з полями, 404, 409 з conflicts[] і
 * `stale-data` для застарілої `version`, 422 з кодами правил), переходи станів з історією, SSE
 * `change.updated` / `absence.created` / `absence.updated`, запит у Telegram і його імітована відповідь.
 */
import type {
  AbsenceDto,
  AbsenceReasonCategory,
  AbsenceRequest,
  AbsenceSummaryDto,
  ChangeKind,
  Conflict,
  CreateChangeRequest,
  FieldErrorDto,
  LessonView,
  ReasonCode,
  ReplyDecision,
  UpdateChangeRequest,
  Uuid,
} from '@/api/types';
import { compareIsoDates, formatDateRange, formatNumericDate, isIsoDate } from '@/lib/date';
import { CHANGE_KIND_LABEL } from '@/lib/labels';
import {
  ACTIVE_SEMESTER,
  disciplineRef,
  findDiscipline,
  findGroup,
  findRoom,
  findTeacher,
  groupRef,
  roomRef,
  teacherRef,
} from '../../fixtures';
import { emitServerEvent } from '../../events';
import { mockId } from '../../ids';
import { badRequest, businessRule, conflict, problem } from '../../kit';
import { currentSettings } from '../settings';
import { isTeacherBound, recordConfirmationRequest } from '../telegram';
import { makeChange, transition } from './factory';
import { printedReason } from './printed';
import { findTemplateLesson } from './lessons';
import { decisionFor, groupPairs, isAbsent, isCovered, teacherSlots } from './rules';
import { getChange, saveChange, world } from './state';
import { cancelSimulatedReply, scheduleSimulatedReply, simulatedDecision } from './telegram-sim';
import { ADMIN_ACTOR, toChangeDto, type ChangeRecord } from './world';

const REASON_CATEGORIES: readonly AbsenceReasonCategory[] = ['SICK_LEAVE', 'VACATION', 'BUSINESS_TRIP', 'PERSONAL', 'OTHER'];
const REASON_CODES: readonly ReasonCode[] = ['ABSENT', 'MUTUAL', 'FINISHED', 'BUSINESS_TRIP', 'OTHER'];
/** Види, які диспетчер створює зі сторінки «Заміни» (блок і «вичитано» — з календаря й обліку). */
const CREATABLE_KINDS: readonly ChangeKind[] = ['REPLACE_TEACHER', 'REPLACE_DISCIPLINE', 'SWAP', 'CANCEL', 'ADDITIONAL', 'ROOM_CHANGE'];
const MAX_PAIR = 8;
/** Межі core: коментар відсутності до 1000 символів, текст причини зміни до 100. */
const MAX_COMMENT = 1000;
const MAX_REASON_TEXT = 100;
/** Діапазон пар (pairTo ≠ pairFrom) core дозволяє лише блоку й додатковим парам. */
const RANGE_KINDS: readonly ChangeKind[] = ['BLOCK', 'ADDITIONAL'];

function emitChange(change: ChangeRecord): void {
  emitServerEvent('change.updated', toChangeDto(change));
}

/** 409 `stale-data`, як OptimisticLockingFailureException у core. */
function staleData(): Response {
  return problem({
    status: 409,
    slug: 'stale-data',
    title: 'Дані змінилися',
    detail: 'Запис щойно змінив інший користувач. Оновіть сторінку та повторіть дію.',
  });
}

/** Подія SSE про відсутність — без причини й коментаря (вони приватні, DOMAIN §7). */
export function absenceSummary(absence: AbsenceDto): AbsenceSummaryDto {
  const { id, teacher, dateFrom, dateTo, pairs, source, status } = absence;
  return { id, teacher, dateFrom, dateTo, pairs, source, status };
}

const semesterRange = `${formatNumericDate(ACTIVE_SEMESTER.startsOn)}–${formatNumericDate(ACTIVE_SEMESTER.endsOn)}`;

// ───────────────────────── Відсутності ─────────────────────────

/** Перевіряє тіло відсутності; повертає помилки полів (порожній масив — усе гаразд). */
export function validateAbsence(body: Partial<AbsenceRequest>): FieldErrorDto[] {
  const errors: FieldErrorDto[] = [];
  if (!body.teacherId || !findTeacher(body.teacherId)) errors.push({ field: 'teacherId', message: 'Оберіть викладача' });
  const fromValid = isIsoDate(body.dateFrom);
  const toValid = isIsoDate(body.dateTo);
  if (!fromValid) errors.push({ field: 'dateFrom', message: 'Вкажіть дату початку' });
  if (!toValid) errors.push({ field: 'dateTo', message: 'Вкажіть дату завершення' });
  if (fromValid && toValid) {
    const from = body.dateFrom as string;
    const to = body.dateTo as string;
    if (compareIsoDates(to, from) < 0) errors.push({ field: 'dateTo', message: 'Не раніше за дату початку' });
    else if (compareIsoDates(from, ACTIVE_SEMESTER.startsOn) < 0 || compareIsoDates(to, ACTIVE_SEMESTER.endsOn) > 0) {
      errors.push({ field: 'dateFrom', message: `Дати мають бути в межах семестру (${semesterRange})` });
    }
  }
  if (body.pairs !== null && body.pairs !== undefined) {
    const pairs = body.pairs;
    const valid =
      Array.isArray(pairs) &&
      pairs.length > 0 &&
      pairs.every((p) => Number.isInteger(p) && p >= 1 && p <= MAX_PAIR) &&
      new Set(pairs).size === pairs.length;
    if (!valid) errors.push({ field: 'pairs', message: `Оберіть пари від 1 до ${MAX_PAIR} або «увесь день»` });
  }
  if (!body.reasonCategory || !REASON_CATEGORIES.includes(body.reasonCategory)) {
    errors.push({ field: 'reasonCategory', message: 'Оберіть причину' });
  }
  if (body.comment && body.comment.length > MAX_COMMENT) errors.push({ field: 'comment', message: `Не довше ${MAX_COMMENT} символів` });
  return errors;
}

function overlaps(a: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>, b: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>): boolean {
  if (compareIsoDates(a.dateTo, b.dateFrom) < 0 || compareIsoDates(b.dateTo, a.dateFrom) < 0) return false;
  if (a.pairs === null || b.pairs === null) return true;
  return a.pairs.some((pair) => b.pairs?.includes(pair));
}

function assertNoOverlap(candidate: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>, teacherId: Uuid, ignoreId?: Uuid): void {
  for (const other of world().absences.values()) {
    if (other.id === ignoreId || other.status !== 'ACTIVE' || other.teacher.id !== teacherId) continue;
    if (overlaps(candidate, other)) {
      throw conflict(
        `У ${other.teacher.shortName} уже є відсутність на ${formatDateRange(other.dateFrom, other.dateTo)}. Змініть її замість нової.`,
      );
    }
  }
}

export function createAbsence(body: Partial<AbsenceRequest>, now: Date): AbsenceDto {
  const errors = validateAbsence(body);
  if (errors.length > 0) throw badRequest('Перевірте дані відсутності.', errors);
  const teacher = findTeacher(body.teacherId as string);
  if (!teacher) throw badRequest('Невідомий викладач.', [{ field: 'teacherId', message: 'Оберіть викладача' }]);
  const pairs = body.pairs ? [...body.pairs].sort((a, b) => a - b) : null;
  const draft = { dateFrom: body.dateFrom as string, dateTo: body.dateTo as string, pairs };
  assertNoOverlap(draft, teacher.id);
  const absence: AbsenceDto = {
    id: mockId('absence', `${teacher.id}@${now.toISOString()}`),
    teacher: teacherRef(teacher),
    ...draft,
    reasonCategory: body.reasonCategory as AbsenceReasonCategory,
    comment: body.comment?.trim() || null,
    source: 'ADMIN',
    status: 'ACTIVE',
    createdBy: ADMIN_ACTOR,
    createdAt: now.toISOString(),
    version: 0,
  };
  world().absences.set(absence.id, absence);
  emitServerEvent('absence.created', absenceSummary(absence));
  return absence;
}

export function getAbsence(id: Uuid): AbsenceDto {
  const absence = world().absences.get(id);
  if (!absence || absence.status !== 'ACTIVE') {
    throw problem({ status: 404, detail: `Відсутність з id ${id} не знайдено.` });
  }
  return absence;
}

export function updateAbsence(id: Uuid, body: Partial<AbsenceRequest>): AbsenceDto {
  const current = world().absences.get(id);
  if (!current) throw problem({ status: 404, detail: `Відсутність з id ${id} не знайдено.` });
  if (body.version !== undefined && body.version !== null && body.version !== current.version) throw staleData();
  if (current.status === 'CANCELLED') throw businessRule('ABSENCE_CANCELLED', 'Скасовану відсутність змінити не можна.');
  const errors = validateAbsence(body);
  if (errors.length > 0) throw badRequest('Перевірте дані відсутності.', errors);
  if (body.teacherId !== current.teacher.id) {
    throw businessRule('ABSENCE_TEACHER_FIXED', 'Викладача відсутності змінити не можна: скасуйте її та створіть нову.');
  }
  const pairs = body.pairs ? [...body.pairs].sort((a, b) => a - b) : null;
  const next: AbsenceDto = {
    ...current,
    dateFrom: body.dateFrom as string,
    dateTo: body.dateTo as string,
    pairs,
    reasonCategory: body.reasonCategory as AbsenceReasonCategory,
    comment: body.comment?.trim() || null,
    version: current.version + 1,
  };
  assertNoOverlap(next, current.teacher.id, id);
  world().absences.set(id, next);
  emitServerEvent('absence.updated', absenceSummary(next));
  return next;
}

/** Відсутність скасовано (викладач вийшов): невнесені рішення за нею теж скасовуються. */
export function cancelAbsence(id: Uuid, now: Date): void {
  const absence = world().absences.get(id);
  if (!absence) throw problem({ status: 404, detail: `Відсутність з id ${id} не знайдено.` });
  if (absence.status === 'CANCELLED') throw businessRule('ABSENCE_CANCELLED', 'Ця відсутність уже скасована.');
  const cancelled: AbsenceDto = { ...absence, status: 'CANCELLED', version: absence.version + 1 };
  world().absences.set(id, cancelled);
  emitServerEvent('absence.updated', absenceSummary(cancelled));
  for (const change of [...world().changes.values()]) {
    if (change.absenceId !== id || change.status === 'APPLIED' || change.status === 'CANCELLED') continue;
    cancelSimulatedReply(change.id);
    emitChange(saveChange(transition(change, 'CANCELLED', now, ADMIN_ACTOR, 'Відсутність скасовано', { confirmDeadline: null })));
  }
}

// ───────────────────────── Зміни: створення ─────────────────────────

interface Resolved {
  lesson: LessonView | null;
  swapLesson: LessonView | null;
}

function lessonOn(id: string | null | undefined, date: string, field: string, errors: FieldErrorDto[]): LessonView | null {
  if (!id) {
    errors.push({ field, message: 'Оберіть пару' });
    return null;
  }
  const found = findTemplateLesson(id);
  if (!found || found.date !== date) {
    errors.push({ field, message: 'Такої пари на цю дату немає' });
    return null;
  }
  return found.lesson;
}

function teacherConflict(teacherId: Uuid, date: string, pair: number): Conflict | null {
  const teacher = findTeacher(teacherId);
  const name = teacher?.shortName ?? 'Викладач';
  if (isAbsent(world(), teacherId, date, pair)) {
    return { kind: 'UNAVAILABLE', severity: 'HARD', message: `У ${name} на цю пару є відсутність`, lessonIds: [], date, pair };
  }
  const slot = teacherSlots(world(), teacherId, date).get(pair);
  if (!slot) return null;
  return {
    kind: 'TEACHER',
    severity: 'HARD',
    message: `${name} у цей час уже веде ${slot.label}`,
    lessonIds: slot.lessonId ? [slot.lessonId] : [],
    date,
    pair,
  };
}

function resolveRequest(body: Partial<CreateChangeRequest>): Resolved & { errors: FieldErrorDto[] } {
  const errors: FieldErrorDto[] = [];
  const kind = body.kind;
  const result: Resolved = { lesson: null, swapLesson: null };
  if (!kind || !CREATABLE_KINDS.includes(kind)) {
    errors.push({ field: 'kind', message: 'Цей вид зміни тут не створюється' });
    return { ...result, errors };
  }
  if (!isIsoDate(body.date)) errors.push({ field: 'date', message: 'Вкажіть дату' });
  else if (compareIsoDates(body.date, ACTIVE_SEMESTER.startsOn) < 0 || compareIsoDates(body.date, ACTIVE_SEMESTER.endsOn) > 0) {
    errors.push({ field: 'date', message: `Дата має бути в межах семестру (${semesterRange})` });
  }
  if (!body.groupId || !findGroup(body.groupId)) errors.push({ field: 'groupId', message: 'Оберіть групу' });
  if (!Number.isInteger(body.pairFrom) || (body.pairFrom as number) < 1 || (body.pairFrom as number) > MAX_PAIR) {
    errors.push({ field: 'pairFrom', message: `Пара від 1 до ${MAX_PAIR}` });
  }
  if (body.pairTo !== undefined && body.pairTo !== null && body.pairTo !== body.pairFrom && !RANGE_KINDS.includes(kind)) {
    errors.push({ field: 'pairTo', message: 'Діапазон пар можливий лише для блоку або додаткових пар' });
  }
  if (!body.reasonCode || !REASON_CODES.includes(body.reasonCode)) errors.push({ field: 'reasonCode', message: 'Оберіть причину' });
  if (body.reasonCode === 'OTHER' && !body.reasonText?.trim()) {
    errors.push({ field: 'reasonText', message: 'Коротко вкажіть причину для розпорядження' });
  }
  if (body.reasonText && body.reasonText.trim().length > MAX_REASON_TEXT) {
    errors.push({ field: 'reasonText', message: `Не довше ${MAX_REASON_TEXT} символів: текст іде в колонку розпорядження` });
  }
  if (body.absenceId && world().absences.get(body.absenceId)?.status !== 'ACTIVE') {
    errors.push({ field: 'absenceId', message: 'Відсутність не знайдено або скасовано' });
  }
  if (errors.length > 0) return { ...result, errors };
  const date = body.date as string;

  if (kind !== 'ADDITIONAL') {
    result.lesson = lessonOn(body.originalLessonId, date, 'originalLessonId', errors);
    if (result.lesson && (result.lesson.group.id !== body.groupId || result.lesson.pair !== body.pairFrom)) {
      errors.push({ field: 'originalLessonId', message: 'Пара не збігається з групою чи номером пари' });
    }
  }
  const needsTeacher = kind === 'REPLACE_TEACHER' || kind === 'REPLACE_DISCIPLINE' || kind === 'ADDITIONAL';
  if (needsTeacher && (!body.newTeacherId || !findTeacher(body.newTeacherId))) {
    errors.push({ field: 'newTeacherId', message: 'Оберіть викладача' });
  }
  if (needsTeacher && result.lesson && body.newTeacherId === result.lesson.teacher?.id) {
    errors.push({ field: 'newTeacherId', message: 'Це той самий викладач' });
  }
  if ((kind === 'REPLACE_DISCIPLINE' || kind === 'ADDITIONAL') && (!body.newDisciplineId || !findDiscipline(body.newDisciplineId))) {
    errors.push({ field: 'newDisciplineId', message: 'Оберіть дисципліну' });
  }
  if (kind === 'ROOM_CHANGE' && (!body.newRoomId || !findRoom(body.newRoomId))) {
    errors.push({ field: 'newRoomId', message: 'Оберіть аудиторію' });
  }
  if (kind === 'SWAP') {
    result.swapLesson = lessonOn(body.swapLessonId, date, 'swapLessonId', errors);
    const swap = result.swapLesson;
    if (swap && result.lesson && (swap.group.id !== result.lesson.group.id || swap.pair <= result.lesson.pair)) {
      errors.push({ field: 'swapLessonId', message: 'Переставити можна лише пізнішу пару тієї ж групи' });
    }
  }
  return { ...result, errors };
}

/** POST /api/admin/changes: перевірка, конфлікти й статус за режимом погодження. */
export function createChange(body: Partial<CreateChangeRequest>, now: Date): ChangeRecord {
  const { lesson, swapLesson, errors } = resolveRequest(body);
  if (errors.length > 0) throw badRequest('Перевірте дані зміни.', errors);
  const kind = body.kind as ChangeKind;
  const date = body.date as string;
  const pair = body.pairFrom as number;

  if (lesson) {
    const decision = decisionFor(world(), lesson.id);
    if (decision && isCovered(decision)) {
      throw conflict(
        `Для цієї пари вже є рішення: ${CHANGE_KIND_LABEL[decision.kind].toLocaleLowerCase('uk')}. Скасуйте його, щоб обрати інше.`,
      );
    }
  }
  const conflicts: Conflict[] = [];
  const newTeacherId = kind === 'SWAP' ? swapLesson?.teacher?.id : body.newTeacherId;
  if (newTeacherId) {
    const busy = teacherConflict(newTeacherId, date, pair);
    if (busy) conflicts.push(busy);
  }
  if (kind === 'ADDITIONAL' && body.groupId && groupPairs(world(), body.groupId, date).includes(pair)) {
    conflicts.push({ kind: 'GROUP', severity: 'HARD', message: 'У групи на цій парі вже є заняття', lessonIds: [], date, pair });
  }
  if (conflicts.length > 0) throw conflict('Зміна конфліктує з розкладом.', conflicts);

  const group = findGroup(body.groupId as string);
  const settings = currentSettings();
  const newTeacher = body.newTeacherId ? findTeacher(body.newTeacherId) : undefined;
  const newDiscipline = body.newDisciplineId ? findDiscipline(body.newDisciplineId) : undefined;
  const newRoom = body.newRoomId ? findRoom(body.newRoomId) : undefined;
  const change = makeChange(
    {
      id: mockId('change', `${body.originalLessonId ?? body.groupId}@${now.toISOString()}#${world().changes.size}`),
      kind,
      date,
      group: group ? groupRef(group) : (lesson?.group as LessonView['group']),
      subgroup: lesson?.subgroup ?? body.subgroup ?? 0,
      pairFrom: pair,
      pairTo: body.pairTo ?? null,
      lesson,
      swapLesson,
      newTeacher: newTeacher ? teacherRef(newTeacher) : null,
      newDiscipline: newDiscipline ? disciplineRef(newDiscipline) : null,
      newRoom: newRoom ? roomRef(newRoom) : null,
      reasonCode: body.reasonCode as ReasonCode,
      reasonText: body.reasonText ?? null,
      absenceId: body.absenceId ?? null,
    },
    { at: now, actor: ADMIN_ACTOR, mode: settings.approvalMode, timeoutMinutes: settings.confirmTimeoutMinutes },
  );
  saveChange(change);
  emitChange(change);
  if (change.status === 'PENDING_CONFIRMATION') requestConfirmation(change);
  return change;
}

// ───────────────────────── Зміни: життєвий цикл ─────────────────────────

/** Надсилає запит викладачу в Telegram і (у браузерному моку) планує його відповідь. */
function requestConfirmation(change: ChangeRecord): void {
  const teacherId = change.new.teacher?.id;
  if (!teacherId) return;
  recordConfirmationRequest(change.id, teacherId);
  // запит без привʼязаного чату не доходить — відповіді не буде, диспетчер підтверджує вручну
  if (!isTeacherBound(teacherId)) return;
  scheduleSimulatedReply(
    change.id,
    () => {
      const current = world().changes.get(change.id);
      if (!current || current.status !== 'PENDING_CONFIRMATION') return null;
      const pairs = teacherSlots(world(), teacherId, current.date).size - 1;
      return simulatedDecision(current.id, pairs);
    },
    (decision, comment) => {
      const current = world().changes.get(change.id);
      if (current?.status === 'PENDING_CONFIRMATION') replyToChange(current.id, teacherId, decision, comment, new Date());
    },
  );
}

/** DELETE /api/admin/changes/{id} → CANCELLED: пара знову потребує рішення. Повтор — без помилки (204). */
export function cancelChange(id: Uuid, now: Date): ChangeRecord {
  const change = getChange(id);
  if (change.status === 'CANCELLED') return change;
  if (change.status === 'APPLIED') {
    throw businessRule('CHANGE_ALREADY_APPLIED', 'Зміну вже внесено в опубліковане розпорядження — скасувати її можна лише доповненням.');
  }
  cancelSimulatedReply(id);
  const next = saveChange(transition(change, 'CANCELLED', now, ADMIN_ACTOR, 'Рішення скасовано диспетчером', { confirmDeadline: null }));
  emitChange(next);
  return next;
}

/**
 * POST …/request-confirmation: нагадування в Telegram і новий строк відповіді. Як у core, запит можна надіслати
 * й після відмови (DECLINED → PENDING_CONFIRMATION) — наприклад, коли диспетчер домовився телефоном.
 */
export function resendConfirmation(id: Uuid, now: Date): ChangeRecord {
  const change = getChange(id);
  if (change.status !== 'PENDING_CONFIRMATION' && change.status !== 'DECLINED') {
    throw businessRule('CHANGE_ILLEGAL_TRANSITION', 'Запит можна надіслати лише для заміни, що чекає відповіді або була відхилена.');
  }
  const deadline = new Date(now.getTime() + currentSettings().confirmTimeoutMinutes * 60_000).toISOString();
  const next = saveChange(
    transition(change, 'PENDING_CONFIRMATION', now, ADMIN_ACTOR, 'Запит надіслано повторно', { confirmDeadline: deadline }),
  );
  emitChange(next);
  requestConfirmation(next);
  return next;
}

/** POST …/confirm: диспетчер підтверджує сам (домовився телефоном). Відхилену core так не підтверджує (422). */
export function confirmByAdmin(id: Uuid, now: Date): ChangeRecord {
  const change = getChange(id);
  if (change.status === 'DECLINED') {
    throw businessRule('CHANGE_DECLINED', 'Викладач відмовився від цієї заміни. Надішліть запит ще раз або оберіть іншого викладача.');
  }
  if (change.status !== 'PENDING_CONFIRMATION' && change.status !== 'DRAFT') {
    throw businessRule('CHANGE_ILLEGAL_TRANSITION', 'Підтвердити можна лише заміну, що чекає відповіді.');
  }
  cancelSimulatedReply(id);
  const next = saveChange(transition(change, 'CONFIRMED', now, ADMIN_ACTOR, 'Підтверджено диспетчером', { confirmDeadline: null }));
  emitChange(next);
  return next;
}

/** POST /api/internal/changes/{id}/reply — так бот передає відповідь викладача. */
export function replyToChange(id: Uuid, teacherId: string | undefined, decision: ReplyDecision | undefined, comment: string | null | undefined, now: Date): ChangeRecord {
  if (decision !== 'CONFIRM' && decision !== 'DECLINE') {
    throw badRequest('Невідома відповідь.', [{ field: 'decision', message: 'CONFIRM або DECLINE' }]);
  }
  const change = getChange(id);
  if (!teacherId || change.new.teacher?.id !== teacherId) {
    throw problem({ status: 403, detail: 'Відповісти може лише викладач, якому запропоновано заміну.' });
  }
  if (change.status !== 'PENDING_CONFIRMATION') throw conflict('Заміна вже не чекає підтвердження.');
  cancelSimulatedReply(id);
  const to = decision === 'CONFIRM' ? 'CONFIRMED' : 'DECLINED';
  const next = saveChange(
    transition(change, to, now, change.new.teacher?.shortName ?? 'telegram', comment?.trim() || null, {
      respondedAt: now.toISOString(),
      confirmDeadline: null,
      responseComment: comment?.trim() || null,
    }),
  );
  emitChange(next);
  return next;
}

/**
 * PUT /api/admin/changes/{id} `{newRoomId?, reasonCode, reasonText?, version}` — як ChangeCommands.update у core:
 * лише аудиторія й причина; без `version` → 400 (поле version), застаріла → 409 `stale-data`;
 * редагувати можна відкриту чи відхилену.
 * `newRoomId` замінює аудиторію рішення (null — без окремої аудиторії).
 */
export function updateChange(id: Uuid, body: Partial<UpdateChangeRequest>): ChangeRecord {
  const change = getChange(id);
  if (body.version === undefined || body.version === null) {
    throw badRequest('Перевірте дані зміни.', [{ field: 'version', message: 'Вкажіть версію зміни (поле version з відповіді)' }]);
  }
  if (body.version !== change.version) throw staleData();
  const editable = change.status === 'DRAFT' || change.status === 'PENDING_CONFIRMATION' || change.status === 'CONFIRMED' || change.status === 'DECLINED';
  if (!editable) throw businessRule('CHANGE_NOT_EDITABLE', 'Зміну з цим статусом редагувати не можна.');
  const errors: FieldErrorDto[] = [];
  if (!body.reasonCode || !REASON_CODES.includes(body.reasonCode)) errors.push({ field: 'reasonCode', message: 'Оберіть причину' });
  const reasonText = body.reasonText?.trim() || null;
  if (reasonText && reasonText.length > MAX_REASON_TEXT) errors.push({ field: 'reasonText', message: `Не довше ${MAX_REASON_TEXT} символів` });
  const room = body.newRoomId ? findRoom(body.newRoomId) : undefined;
  if (body.newRoomId && !room) throw businessRule('REFERENCE_NOT_FOUND', 'Аудиторію не знайдено.');
  if (errors.length > 0) throw badRequest('Перевірте дані зміни.', errors);
  const reasonCode = body.reasonCode as ReasonCode;
  if (change.kind === 'DISCIPLINE_FINISHED' && reasonCode !== 'FINISHED') {
    throw businessRule('CHANGE_INVALID', 'Для вичитаної дисципліни причина — «Вичит.».');
  }
  if (change.kind === 'ROOM_CHANGE' && !room) throw businessRule('CHANGE_INVALID', 'Для заміни аудиторії вкажіть нову аудиторію.');
  if (change.kind === 'CANCEL' && room) throw businessRule('CHANGE_INVALID', 'Скасована пара не має аудиторії.');
  const next = saveChange({
    ...change,
    reasonCode,
    reasonText,
    printedReason: printedReason(change.kind, reasonCode, reasonText),
    new: { ...change.new, room: room ? roomRef(room) : null },
    version: change.version + 1,
  });
  emitChange(next);
  return next;
}

/** На публікації підтверджені зміни розпорядження стають внесеними (DOMAIN §7). */
export function applyChanges(ids: readonly Uuid[], orderId: Uuid, number: number, now: Date): ChangeRecord[] {
  const applied: ChangeRecord[] = [];
  for (const id of new Set(ids)) {
    const change = world().changes.get(id);
    if (!change || change.status !== 'CONFIRMED') continue;
    const next = saveChange(transition(change, 'APPLIED', now, ADMIN_ACTOR, `Внесено в розпорядження №${number}`, { orderId }));
    emitChange(next);
    applied.push(next);
  }
  return applied;
}
