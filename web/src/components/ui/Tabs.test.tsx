import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Tabs, TabsList, TabsTrigger } from './Tabs';

describe('Tabs', () => {
  it('кільце фокуса вкладки з клавіатури має суцільний стиль: outline-none у Tailwind v4 інакше ховає його', () => {
    render(
      <Tabs defaultValue="a">
        <TabsList>
          <TabsTrigger value="a">Перша</TabsTrigger>
          <TabsTrigger value="b">Друга</TabsTrigger>
        </TabsList>
      </Tabs>,
    );
    for (const tab of screen.getAllByRole('tab')) {
      expect(tab).toHaveClass('focus-visible:outline-2', 'focus-visible:outline-solid');
    }
  });
});
