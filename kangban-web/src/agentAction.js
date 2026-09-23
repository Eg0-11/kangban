export function parseActionProposalEvent(data) {
  try {
    const parsed = JSON.parse(data);
    if (!parsed?.id || !parsed?.type) return null;
    return {
      ...parsed,
      parameters: parsed.parameters && typeof parsed.parameters === 'object' ? parsed.parameters : {},
    };
  } catch {
    return null;
  }
}

export function readActionProposals(message) {
  if (Array.isArray(message?.actions)) return message.actions;
  if (typeof message?.actionsJson !== 'string') return [];
  try {
    const parsed = JSON.parse(message.actionsJson);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}
