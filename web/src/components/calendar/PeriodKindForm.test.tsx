import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PeriodKindDto } from '@/api/types';
import { PERIOD_KINDS } from '@/mocks/fixtures/calendar';
import { renderWithProviders } from '@/test/render';
import { PeriodKindFormDialog } from './PeriodKindForm';

const kindOf = (code: string) => ({ ...PERIOD_KINDS.find((k) => k.code === code), usage: 0 }) as PeriodKindDto;

describe('PeriodKindFormDialog: приклад примітки', () => {
  it('НС — примітка з назвою курсу «Основи національного спротиву», як у розпорядженні, а не з назвою виду', async () => {
    renderWithProviders(<PeriodKindFormDialog kind={kindOf('НС')} open onOpenChange={() => {}} />);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401')).toBeInTheDocument();
    expect(within(dialog).queryByText(/4КІ1 – Національний спротив/)).not.toBeInTheDocument();
  });

  it('вид без відомої назви періоду — приклад з назвою виду', async () => {
    renderWithProviders(<PeriodKindFormDialog kind={kindOf('ЗУ')} open onOpenChange={() => {}} />);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('4КІ1 – Збори з 16.11.26р. по 27.11.26 р., ауд. 401')).toBeInTheDocument();
  });
});
