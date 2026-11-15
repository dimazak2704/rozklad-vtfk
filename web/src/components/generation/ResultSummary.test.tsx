import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { ConstraintAnalysis, GroupComparison, MetricsComparison } from './ResultSummary';

describe('результат генерації: правило «Не враховувати»', () => {
  it('«Було і стало» не фарбує його зміну як погіршення й підписує «не враховувалось»', () => {
    renderWithProviders(
      <MetricsComparison
        rows={[
          { key: 'latePairs', label: '4-та пара зміни', before: 291, after: 300, ignored: true },
          { key: 'groupWindows', label: 'Вікна груп', before: 1, after: 3 },
        ]}
      />,
    );
    const late = screen.getByRole('row', { name: /4-та пара зміни/ });
    expect(late).toHaveTextContent('не враховувалось');
    expect(late.querySelector('[data-tone]')).toHaveAttribute('data-tone', 'neutral');
    expect(screen.getByRole('row', { name: /Вікна груп/ }).querySelector('[data-tone]')).toHaveAttribute('data-tone', 'worse');
  });

  it('«Що лишилось» без нього; він — сірим рядком «не враховувалось»', () => {
    renderWithProviders(
      <ConstraintAnalysis
        scoped={false}
        ignored={new Set(['latePairs'])}
        items={[
          { constraint: '4-та пара зміни', constraintId: 'Late pair', matchCount: 59, score: '0hard/0soft' },
          { constraint: 'Вікна у викладача', constraintId: 'Teacher windows', matchCount: 26, score: '0hard/-260soft' },
        ]}
      />,
    );
    const section = screen.getByRole('heading', { name: 'Що лишилось' }).closest('section') as HTMLElement;
    const remaining = within(section).getAllByRole('list')[0] as HTMLElement;
    expect(remaining).toHaveTextContent('Вікна у викладача');
    expect(remaining).not.toHaveTextContent('4-та пара зміни');
    expect(within(section).getByRole('list', { name: 'Не враховувалось' })).toHaveTextContent(/4-та пара зміни · не враховувалось\s*59\sразів/);
  });
});

describe('По групах на телефоні', () => {
  it('картка на групу: кожен показник, і «Переставлено», — своїм рядком, без таблиці, що ховає колонки', () => {
    renderWithProviders(
      <GroupComparison
        rows={[
          {
            group: { id: 'g', code: '4КІ2', course: 4 },
            cells: [
              { key: 'latePairs', before: 4, after: 2 },
              { key: 'windows', before: 0, after: 0 },
              { key: 'brokenDays', before: 1, after: 1 },
              { key: 'onePairDays', before: 1, after: 0 },
            ],
            moved: { moved: 2, total: 18 },
          },
        ]}
      />,
    );
    const card = within(screen.getByRole('list', { name: 'По групах: картки' })).getByRole('listitem');
    expect(within(card).getByText('Дні з однією парою')).toBeInTheDocument();
    expect(within(card).getByText('Переставлено').nextElementSibling).toHaveTextContent('2 з 18');
    expect(card.closest('ul')).toHaveClass('sm:hidden');
    // таблиця — лише з ширини sm
    expect(screen.getByRole('table', { hidden: true }).parentElement?.parentElement).toHaveClass('hidden', 'sm:block');
  });
});
