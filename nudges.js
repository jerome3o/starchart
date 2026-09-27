'use strict';

// Hourly nudges: for each goal the user is behind on, a cheap classifier
// (Claude Haiku) decides whether now is a good moment; if so Claude writes a
// sassy notification and it goes to the phone through the command queue.
// Hard limits here keep it from nagging no matter what the models say.

const db = require('./db');
const goals = require('./goals');
const time = require('./time');
const claude = require('./claude');

const QUIET_BEFORE_HOUR = 8; // local time
const QUIET_FROM_HOUR = 22;
const MIN_GAP_PER_GOAL_MS = 4 * 3600e3;
const MAX_PER_DAY = 5;
const HOUR_MS = 3600e3;

const pad = (n) => String(n).padStart(2, '0');
const localLabel = (ms, tz) => {
  const p = time.localParts(ms, tz);
  return `${p.weekday} ${pad(p.hour)}:${pad(p.minute)}`;
};

/** Candidate goals for one user right now, with the context the models see. */
function candidates(email, nowMs = Date.now()) {
  const tz = db.getTimezone(email);
  const local = time.localParts(nowMs, tz);
  if (local.hour < QUIET_BEFORE_HOUR || local.hour >= QUIET_FROM_HOUR) return { skip: 'quiet hours', list: [] };
  const today = time.localMidnightUtc(local.year, local.month, local.day, tz);
  const sentToday = db.nudgesSince(email, today);
  if (sentToday.length >= MAX_PER_DAY) return { skip: 'daily cap', list: [] };

  const data = goals.goalsWithProgress(email, nowMs);
  const list = [];
  for (const g of data.goals) {
    if (g.status !== 'behind' || g.count >= g.target) continue;
    const lastForGoal = sentToday.find((n) => n.goal_id === g.id) ||
      db.nudgesSince(email, nowMs - MIN_GAP_PER_GOAL_MS).find((n) => n.goal_id === g.id);
    if (lastForGoal && nowMs - lastForGoal.time < MIN_GAP_PER_GOAL_MS) continue;
    const recent = db.recentCompletionTimes(g.id, 15);
    list.push({
      goal: g,
      context: {
        now_local: claude.nowLine(email),
        goal: g.name,
        emoji: g.emoji,
        why_and_description: g.description || '(none given)',
        done_this_period: g.count,
        target_this_period: g.target,
        expected_by_now: g.target_by_now,
        behind_by: Math.round((g.target_by_now - g.count) * 10) / 10,
        period_days: g.period_days,
        days_left: g.days_left,
        done_today: recent.some((t) => t >= today),
        recent_completions_local: recent.map((t) => localLabel(t, tz)),
        nudges_about_this_goal_today: sentToday.filter((n) => n.goal_id === g.id).map((n) => `${localLabel(n.time, tz)}: ${n.text}`),
        nudges_today_all_goals: sentToday.length,
      },
    });
  }
  return { list };
}

/** One pass for one user; sends at most one nudge. */
async function runForUser(email, log = console.log) {
  if (!db.nudgesEnabled(email)) return null;
  const device = db.mostRecentDevice(email);
  if (!device) return null;
  const { skip, list } = candidates(email);
  if (skip || !list.length) return null;

  // Most behind first; the first one the classifier approves gets the nudge.
  list.sort((a, b) => b.context.behind_by - a.context.behind_by);
  for (const c of list) {
    const decision = await claude.shouldNudge(c.context);
    log(`[nudges] ${email} ${c.goal.name}: ${decision.send ? 'send' : 'skip'} — ${decision.reason}`);
    if (!decision.send) continue;
    const note = await claude.writeNudge(c.context);
    const commandId = db.queuePhoneCommand(device.id, 'notify', { title: note.title, text: note.text, open: 'app' }, 'Starchart nudges');
    db.recordNudge({ email, goalId: c.goal.id, title: note.title, text: note.text, reason: decision.reason, commandId });
    log(`[nudges] sent to ${email}: ${note.title} — ${note.text}`);
    return note;
  }
  return null;
}

async function runAll(log = console.log) {
  if (!claude.enabled()) return;
  for (const email of db.emailsWithGoals()) {
    try {
      await runForUser(email, log);
    } catch (e) {
      log(`[nudges] ${email} failed: ${e.message}`);
    }
  }
}

/** Runs a few minutes past every hour. */
function start(log = console.log) {
  if (!claude.enabled()) {
    log('[nudges] ANTHROPIC_API_KEY not set; nudges and chat are off');
    return;
  }
  const schedule = () => {
    const now = Date.now();
    let next = Math.floor(now / HOUR_MS) * HOUR_MS + 5 * 60e3;
    if (next <= now) next += HOUR_MS;
    setTimeout(() => { runAll(log).finally(schedule); }, next - now).unref();
  };
  schedule();
  log('[nudges] scheduled hourly');
}

module.exports = { start, runAll, runForUser, candidates };
