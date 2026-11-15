import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { ConstraintAnalysis, MetricsComparison } from './ResultSummary';

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
