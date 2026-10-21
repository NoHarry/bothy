'use strict';

// One webhook call per round. The Grok routine's own instruction says how to report; the payload carries the task,
// how to report this round (the MCP tool, or a callback URL and token), the caller's context, and notes that keep the
// report honest. A webhook run is a sub-run recorded only in the routine's run history (the bot said so when asked
// through the relay, 2026-10-12); WakeParent is how it leaves a line in the user's main chat.
const WEBHOOK_TIMEOUT_MS = 15_000;
const NOTES = '实际执行的时间、对象或内容和要求不一样时，必须在 text 里说明。completed 要在操作真正生效之后再汇报。' +
  '有具体安排（时间、收件人、订单号等）时，可以在汇报的 data 里写明，例如 {"scheduled_at":"2026-10-12T14:00:00+08:00"}。' +
  '地点、价格、推荐项这类适合在设备屏幕上看的结果做成 data.card，有照片或地图截图放 data.images（https 图片地址，最多 3 张）。' +
  '要分几步做的任务：开始后先用 state=working 汇报一次计划，data.steps 列出步骤（[{"title":"…","status":"todo"}]），' +
  '之后每做完一步再用 working 汇报一次，更新各步的 status（done / doing / todo），text 写一句现在在做什么；一步就能做完的不用。' +
  '每一轮汇报之后都要调用 WakeParent，把这一轮同步到主聊天，格式：「设备委托（第 <round> 轮）：<instruction 原文，超过 100 字就截断> → ' +
  '<你汇报的结果，或你问我的问题>」。例行任务的运行默认只记在运行历史里，我在主聊天看不到，所以每一轮都不能省。';

const REPORT_WITH = 'car-relay 插件的 car_task_report：这一轮结束时调用一次，带上 task_id、round、state、text（可选 data）；' +
  '需要完整内容或之前的问答时调用 car_task_get。';

/** The routine's own instruction, shown on the /connect page and in the README. */
const ROUTINE_TEXT = [
  '你收到的 JSON 是我设备上的语音助手委托给你的任务，字段有 task_id、round、instruction、report_with、context、notes。请执行 instruction，并遵守 notes。',
  '这一轮结束时必须调用 car-relay 插件的 car_task_report 汇报一次，不管成功还是失败：带上 task_id、round、state（completed / input-required / failed）、' +
    'text（一两句适合语音播报的中文）和可选的 data。需要完整内容或之前的问答时调用 car_task_get。不要用终端或 curl 回调。',
  '- 需要我做决定或补充信息时，state 写 input-required，text 写清楚要问我什么。',
  '- 付款、下单、删除这类操作，除非 instruction 里明确写了"已确认"，否则一律不执行，改用 input-required 问我。',
  '- 实际执行的时间、对象或内容和要求不一样时，必须在 text 里说明；completed 要在操作真正生效之后再汇报。',
  '- text 里不要包含密码、令牌等敏感信息。',
  '- 每一轮汇报之后调用 WakeParent，把设备发来的内容和结果同步到我们的主聊天。',
].join('\n');

/** Round 2+ restates the original request with every question and answer, since each Grok run starts fresh. */
function followUp(task) {
  const lines = [`继续完成同一个任务（第 ${task.round} 轮）。`, `原任务：${task.instruction}`, '之前的对话：'];
  for (const turn of task.conversation.slice(1)) lines.push(`- ${turn.role === 'agent' ? '你问' : '我答'}：${turn.text}`);
  lines.push('请根据我的回答继续完成原任务，汇报方式不变。');
  return lines.join('\n');
}

/**
 * `target` is where the round goes: `{ publicUrl, webhookUrl, webhookKey, reportWithMcp }`. With the MCP connector the
 * bot reports through `car_task_report` and the payload carries no shell callback at all, so no terminal command needs
 * approval; otherwise it carries the round's callback URL and token.
 */
function payload(target, task, token, now = Date.now) {
  const report = target.reportWithMcp
    ? { report_with: REPORT_WITH }
    : { callback_url: `${target.publicUrl}/cb/${task.id}`, callback_token: token };
  return {
    task_id: task.id,
    round: task.round,
    instruction: task.round > 1 ? followUp(task) : task.instruction,
    ...report,
    context: { ...(task.context || {}), relay_sent_at: new Date(now()).toISOString() },
    notes: NOTES,
  };
}

/** Calls the routine webhook; resolves `{ ok }` or `{ ok: false, reason }` and never throws. */
async function deliver(target, task, token, fetchImpl = fetch) {
  try {
    const response = await fetchImpl(target.webhookUrl, {
      method: 'POST',
      headers: { Authorization: `Bearer ${target.webhookKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload(target, task, token)),
      signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS),
      redirect: 'error',
    });
    await response.body?.cancel().catch(() => {});
    return response.ok ? { ok: true } : { ok: false, reason: `Webhook 返回 HTTP ${response.status}` };
  } catch (error) {
    return { ok: false, reason: error?.name === 'TimeoutError' ? 'Webhook 超时' : 'Webhook 网络错误' };
  }
}

module.exports = { deliver, payload, followUp, NOTES, ROUTINE_TEXT };
