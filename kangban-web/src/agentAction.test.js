import test from 'node:test';
import assert from 'node:assert/strict';
import { parseActionProposalEvent, readActionProposals } from './agentAction.js';

test('parses structured action_proposal SSE payload without accepting malformed data', () => {
  const action = parseActionProposalEvent(JSON.stringify({
    id: 'proposal-1',
    type: 'HEALTH_RECORD',
    status: 'PENDING_CONFIRMATION',
    parameters: { metric: 'blood_pressure', value: '128/82' },
  }));

  assert.equal(action.id, 'proposal-1');
  assert.equal(action.parameters.value, '128/82');
  assert.equal(parseActionProposalEvent('{bad json'), null);
  assert.equal(parseActionProposalEvent(JSON.stringify({ type: 'HEALTH_RECORD' })), null);
});

test('reads persisted action proposals from assistant messages', () => {
  const actions = readActionProposals({
    actionsJson: JSON.stringify([{ id: 'proposal-2', type: 'MEDICATION' }]),
  });
  assert.equal(actions[0].id, 'proposal-2');
});
