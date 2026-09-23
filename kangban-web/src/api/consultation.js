/**
 * AI 问诊相关 API
 */
import { get, post, put } from './request.js';

/** 获取会话历史列表 */
export function getChatSessions({ memberId = null, subjectUserId = null } = {}) {
  return get('/consultation/sessions', { params: { memberId, subjectUserId } });
}

/** 获取会话消息 */
export function getChatMessages(sessionId) {
  return get(`/consultation/sessions/${sessionId}/messages`);
}

/** 发送消息（非流式） */
export function sendMessage(sessionId, content, clientMessageId, options = {}) {
  return post(`/consultation/sessions/${sessionId}/messages`, { content, clientMessageId }, options);
}

/** 创建新会话 */
export function createSession(patientInfo) {
  return post('/consultation/sessions', patientInfo || {});
}

/** 重新读取数据库并追加当前患者健康概况 */
export function appendPatientSummary(sessionId) {
  return post(`/consultation/sessions/${sessionId}/summary`, {});
}

/** 获取问诊历史记录 */
export function getConsultationHistory() {
  return get('/consultation/history');
}

/** 编辑患者资料（问诊侧栏） */
export function updatePatientProfile(data) {
  return put('/consultation/patient', data);
}

/** 获取 Agent 动作草案详情 */
export function getActionProposal(proposalId) {
  return get(`/consultation/actions/${encodeURIComponent(proposalId)}`);
}

/** 确认并执行 Agent 动作草案 */
export function confirmActionProposal(proposalId, payloadHash) {
  return post(`/consultation/actions/${encodeURIComponent(proposalId)}/confirm`, { payloadHash });
}

/** 取消 Agent 动作草案 */
export function cancelActionProposal(proposalId) {
  return post(`/consultation/actions/${encodeURIComponent(proposalId)}/cancel`, {});
}
