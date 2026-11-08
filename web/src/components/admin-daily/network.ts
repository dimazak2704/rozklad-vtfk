/** Запит без власних даних для цієї дати чекає мережі (на паузі, з даними попередньої дати або без даних). */
export function waitsForNetwork(query: { fetchStatus: string; isPending: boolean; isPlaceholderData?: boolean }): boolean {
  return query.fetchStatus === 'paused' && (query.isPending || Boolean(query.isPlaceholderData));
}
