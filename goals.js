'use strict';

// Goal periods, pace and progress — the numbers behind both the phone UI and
// the e-ink dashboard, so they always agree. Mirrors the original TRMNL
// dashboard: fixed-length periods anchored to Sunday 2020-01-05 in the
// user's timezone, "target by now" moving smoothly through the day, and an
// optional grace period (hours_offset) before the day counts.

const express = require('express');
const db = require('./db');
const time = require('./time');

const ANCHOR = [2020, 1, 5]; // a Sunday
const PERIOD_CHOICES = [7, 14];

function currentPeriod(nowMs, timeZone, periodDays) {
  const p = time.localParts(nowMs, timeZone);
  const daysSinceAnchor = time.daysBetween(...ANCHOR, p.year, p.month, p.day);
  const dayOfPeriod = ((daysSinceAnchor % periodDays) + periodDays) % periodDays;
  const [sy, sm, sd] = time.addDays(p.year, p.month, p.day, -dayOfPeriod);
  const [ey, em, ed] = time.addDays(sy, sm, sd, periodDays);
  const startMs = time.localMidnightUtc(sy, sm, sd, timeZone);
  const endMs = time.localMidnightUtc(ey, em, ed, timeZone);
  const dayFraction = (p.hour * 3600 + p.minute * 60 + p.second) / 86400;
  return {
    periodDays,
    startMs,
    endMs,
    startDate: `${sy}-${String(sm).padStart(2, '0')}-${String(sd).padStart(2, '0')}`,
    endDate: (() => {
      const [ly, lm, ld] = time.addDays(ey, em, ed, -1);
      return `${ly}-${String(lm).padStart(2, '0')}-${String(ld).padStart(2, '0')}`;
    })(),
    dayOfPeriod,
    daysElapsed: dayOfPeriod + dayFraction,
    fraction: (dayOfPeriod + dayFraction) / periodDays,
    daysLeft: periodDays - 1 - dayOfPeriod,
    weekday: p.weekday,
  };
}

function targetByNow(goal, period) {
  const elapsed = Math.max(0, period.daysElapsed - goal.hours_offset / 24);
  return goal.target * (elapsed / period.periodDays);
}

function statusFor(count, expected) {
  return count < expected ? 'behind' : 'on_track';
}

// Every active goal with progress for its current period.
function goalsWithProgress(email, nowMs = Date.now()) {
  const timeZone = db.getTimezone(email);
  const goals = db.listGoals(email);
  const periods = new Map();
  for (const g of goals) {
    if (!periods.has(g.period_days)) periods.set(g.period_days, currentPeriod(nowMs, timeZone, g.period_days));
  }
  const counts = new Map();
  for (const [days, period] of periods) {
    const ids = goals.filter((g) => g.period_days === days).map((g) => g.id);
    for (const [id, c] of db.completionCounts(ids, period.startMs, period.endMs)) counts.set(id, c);
  }
  return {
    timezone: timeZone,
    periods: Object.fromEntries([...periods].map(([days, p]) => [days, p])),
    goals: goals.map((g) => {
      const period = periods.get(g.period_days);
      const count = counts.get(g.id) || 0;
      const expected = targetByNow(g, period);
      return {
        id: g.id,
        name: g.name,
        emoji: g.emoji,
        description: g.description || null,
        target: g.target,
        period_days: g.period_days,
        hours_offset: g.hours_offset,
        sort_order: g.sort_order,
        count,
        target_by_now: Math.round(expected * 100) / 100,
        status: statusFor(count, expected),
        days_left: period.daysLeft,
        period_start: period.startDate,
        period_end: period.endDate,
      };
    }),
  };
}

function progressForGoal(email, goalId) {
  return goalsWithProgress(email).goals.find((g) => g.id === goalId) || null;
}

function validateGoalInput(body, { partial = false } = {}) {
  const out = {};
  const errors = [];
  if (!partial || body.name !== undefined) {
    const name = String(body.name || '').trim();
    if (!name || name.length > 60) errors.push('name must be 1-60 characters');
    out.name = name;
  }
  if (body.emoji !== undefined) {
    const emoji = body.emoji === null ? null : String(body.emoji).trim();
    if (emoji && [...emoji].length > 4) errors.push('emoji must be at most 4 characters');
    out.emoji = emoji || null;
  }
  if (body.description !== undefined) {
    const description = body.description === null ? null : String(body.description).trim();
    if (description && description.length > 1000) errors.push('description must be at most 1000 characters');
    out.description = description || null;
  }
  if (!partial || body.target !== undefined) {
    const target = Number(body.target);
    if (!Number.isFinite(target) || target <= 0 || target > 500) errors.push('target must be a number between 0 and 500');
    out.target = target;
  }
  if (body.period_days !== undefined || !partial) {
    const periodDays = body.period_days === undefined ? 14 : Number(body.period_days);
    if (!PERIOD_CHOICES.includes(periodDays)) errors.push(`period_days must be one of ${PERIOD_CHOICES.join(', ')}`);
    out.period_days = periodDays;
  }
  if (body.hours_offset !== undefined || !partial) {
    const hoursOffset = body.hours_offset === undefined ? 0 : Number(body.hours_offset);
    if (!Number.isFinite(hoursOffset) || hoursOffset < 0 || hoursOffset > 48) errors.push('hours_offset must be 0-48');
    out.hours_offset = hoursOffset;
  }
  if (body.sort_order !== undefined) {
    const sortOrder = Number(body.sort_order);
    if (!Number.isInteger(sortOrder)) errors.push('sort_order must be an integer');
    out.sort_order = sortOrder;
  }
  if (body.archived !== undefined) out.archived = body.archived ? 1 : 0;
  return { fields: out, errors };
}

// Records a completion "now" (or at a given time) and returns fresh progress.
function logCompletion(email, goalId, { time: atMs, source, clientId, note }) {
  const goal = db.getGoal(email, goalId);
  if (!goal || goal.archived) return null;
  const result = db.addCompletion(goalId, { time: atMs || Date.now(), source, clientId, note });
  return { ...result, goal: progressForGoal(email, goalId) };
}

function undoCompletion(email, goalId) {
  const goal = db.getGoal(email, goalId);
  if (!goal) return null;
  const period = currentPeriod(Date.now(), db.getTimezone(email), goal.period_days);
  const removed = db.undoLatestCompletion(goalId, period.startMs, period.endMs);
  return { removed, goal: progressForGoal(email, goalId) };
}

// --- Device-token API used by the phone (mounted under /api, after auth) ----

const router = express.Router();

router.get('/goals', (req, res) => {
  const tz = req.query.tz && String(req.query.tz);
  if (tz && time.validTimeZone(tz) && tz !== db.getTimezone(req.device.email)) {
    db.setTimezone(req.device.email, tz);
  }
  res.json(goalsWithProgress(req.device.email));
});

router.post('/goals', (req, res) => {
  const { fields, errors } = validateGoalInput(req.body || {});
  if (errors.length) return res.status(400).json({ error: errors.join('; ') });
  const goal = db.createGoal(req.device.email, {
    name: fields.name, emoji: fields.emoji, target: fields.target,
    periodDays: fields.period_days, hoursOffset: fields.hours_offset, description: fields.description,
  });
  res.status(201).json(progressForGoal(req.device.email, goal.id));
});

// Android's HttpURLConnection can't send PATCH, so POST to the goal is an alias.
const updateHandler = (req, res) => {
  const id = Number(req.params.id);
  if (!db.getGoal(req.device.email, id)) return res.status(404).json({ error: 'no such goal' });
  const { fields, errors } = validateGoalInput(req.body || {}, { partial: true });
  if (errors.length) return res.status(400).json({ error: errors.join('; ') });
  db.updateGoal(req.device.email, id, fields);
  const goal = db.getGoal(req.device.email, id);
  res.json(goal.archived ? { id, archived: true } : progressForGoal(req.device.email, id));
};
router.patch('/goals/:id', updateHandler);
router.post('/goals/:id', updateHandler);

router.delete('/goals/:id', (req, res) => {
  const id = Number(req.params.id);
  if (!db.getGoal(req.device.email, id)) return res.status(404).json({ error: 'no such goal' });
  db.updateGoal(req.device.email, id, { archived: 1 });
  res.json({ id, archived: true });
});

router.post('/goals/:id/completions', (req, res) => {
  const id = Number(req.params.id);
  const body = req.body || {};
  const atMs = body.time !== undefined ? Number(body.time) : undefined;
  if (atMs !== undefined && !Number.isFinite(atMs)) return res.status(400).json({ error: 'time must be epoch ms' });
  const result = logCompletion(req.device.email, id, {
    time: atMs, source: 'app', clientId: body.client_id ? String(body.client_id).slice(0, 64) : null,
    note: body.note ? String(body.note).slice(0, 200) : null,
  });
  if (!result) return res.status(404).json({ error: 'no such goal' });
  res.status(result.created ? 201 : 200).json(result.goal);
});

router.post('/goals/:id/undo', (req, res) => {
  const result = undoCompletion(req.device.email, Number(req.params.id));
  if (!result) return res.status(404).json({ error: 'no such goal' });
  res.json(result.goal);
});

router.get('/goals/:id/completions', (req, res) => {
  const id = Number(req.params.id);
  const goal = db.getGoal(req.device.email, id);
  if (!goal) return res.status(404).json({ error: 'no such goal' });
  const period = currentPeriod(Date.now(), db.getTimezone(req.device.email), goal.period_days);
  const from = req.query.from ? Number(req.query.from) : period.startMs;
  const to = req.query.to ? Number(req.query.to) : period.endMs;
  res.json({ goal_id: id, completions: db.listCompletions(id, from, to) });
});

module.exports = {
  router,
  currentPeriod,
  goalsWithProgress,
  progressForGoal,
  validateGoalInput,
  logCompletion,
  undoCompletion,
  PERIOD_CHOICES,
};
