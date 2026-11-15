import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { TemplateLessonDto, TimetableVersionDto, VersionDiffDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { DiffView, movedKind } from './DiffView';

const lesson = (patch: Partial<TemplateLessonDto>): TemplateLessonDto => ({
  id: 'l',
  weekday: 4,
  pair: 3,
  parity: 'BOTH',
  group: { id: 'g-4ki2', code: '4КІ2', course: 4 },
  subgroup: 0,
  discipline: { id: 'd-ukr', name: 'Українська мова', shortName: 'Укр.мова' },
  teacher: null,
  room: null,
  lessonKind: 'MIXED',
  pinned: false,
  online: false,
  ...patch,
});

const version = (id: string, name: string, status: TimetableVersionDto['status']): TimetableVersionDto =>
  ({ id, name, status, semesterId: 's', source: 'MANUAL', lessonCount: 0 }) as unknown as TimetableVersionDto;

describe('порівняння версій: що сталося з парою', () => {
  it('інший слот — «Переміщено», той самий слот з іншою аудиторією — «Інша аудиторія», аудиторія там, де не було, — «Аудиторію призначено»', () => {
    const room = (name: string) => ({ id: name, name });
    expect(movedKind(lesson({ weekday: 1, pair: 4 }), lesson({ weekday: 1, pair: 1 }))).toBe('moved');
    expect(movedKind(lesson({ parity: 'NUMERATOR' }), lesson({ parity: 'DENOMINATOR' }))).toBe('moved');
    expect(movedKind(lesson({ room: room('301') }), lesson({ room: room('108') }))).toBe('room');
    expect(movedKind(lesson({ room: room('301') }), lesson({ room: null }))).toBe('room');
    expect(movedKind(lesson({ room: null }), lesson({ room: room('108') }))).toBe('roomAssigned');
  });

  it('лічильники перемикача рахують кожен вид окремо — як підсумок генерації', async () => {
    const diff: VersionDiffDto = {
      added: [],
      removed: [],
      moved: [
        { from: lesson({ id: 'a', weekday: 1, pair: 4, room: { id: '301', name: '301' } }), to: lesson({ id: 'a2', weekday: 1, pair: 1, room: { id: '10', name: '10' } }) },
        { from: lesson({ id: 'b' }), to: lesson({ id: 'b2', room: { id: '108', name: '108' } }) },
      ],
    };
    server.use(http.get(apiPath('/admin/timetable/versions/:a/diff/:b'), () => HttpResponse.json(diff)));
    renderWithProviders(
      <DiffView
        versions={[version('pub', 'Розклад 2025/26', 'PUBLISHED'), version('gen', 'Згенеровано', 'DRAFT')]}
        target={version('gen', 'Згенеровано', 'DRAFT')}
        baseId="pub"
        onBaseChange={vi.fn()}
        onShowInGrid={vi.fn()}
      />,
    );
    const rows = await screen.findAllByRole('listitem');
    const filter = screen.getByRole('radiogroup', { name: 'Які зміни показати' });
    expect(within(filter).getByRole('radio', { name: 'Переміщено 1' })).toBeInTheDocument();
    expect(within(filter).getByRole('radio', { name: 'Аудиторію призначено 1' })).toBeInTheDocument();
    expect(within(filter).queryByRole('radio', { name: /Інша аудиторія/ })).not.toBeInTheDocument();
    expect(rows[1]).toHaveTextContent(/Аудиторію призначено.*чт 3, ауд.\s108/);
  });
});
