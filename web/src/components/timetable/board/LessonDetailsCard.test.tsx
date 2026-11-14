import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { TemplateLessonDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { LessonDetailsCard } from './LessonDetailsCard';

const base: TemplateLessonDto = {
  id: 'l-1',
  weekday: 1,
  pair: 1,
  parity: 'BOTH',
  group: { id: 'g-1ea1', code: '1ЕА1', course: 1 },
  subgroup: 0,
  discipline: { id: 'd-en', shortName: 'Ін.мова', name: 'Іноземна мова', color: null },
  teacher: { id: 't-1', shortName: 'Крисак Л.В.', displayName: 'Крисак' },
  room: { id: 'r-110', name: '110', type: 'CLASSROOM' },
  lessonKind: 'PRACTICE',
  loadItemId: null,
  pinned: false,
  online: false,
  streamId: null,
};

describe('LessonDetailsCard: вид заняття', () => {
  it('відомий вид — рядок «Вид»', () => {
    renderWithProviders(<LessonDetailsCard lesson={base} conflicts={[]} />);
    expect(screen.getByText('Вид')).toBeInTheDocument();
    expect(screen.getByText('практичне')).toBeInTheDocument();
  });

  it('змішаний вид (навантаження з розкладу) — рядка «Вид» немає', () => {
    renderWithProviders(<LessonDetailsCard lesson={{ ...base, lessonKind: 'MIXED' }} conflicts={[]} />);
    expect(screen.queryByText('Вид')).not.toBeInTheDocument();
    expect(screen.queryByText('змішане')).not.toBeInTheDocument();
  });
});
