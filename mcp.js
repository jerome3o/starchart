'use strict';

// MCP server (Streamable HTTP, stateless) exposing the signed-in user's
// location data. Every tool is scoped to the email on the bearer token's grant.

const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const { StreamableHTTPServerTransport } = require('@modelcontextprotocol/sdk/server/streamableHttp.js');
const { z } = require('zod');
const db = require('./db');
const goals = require('./goals');

const MAX_RANGE_MS = 31 * 24 * 60 * 60 * 1000;

// Offset (ms, east positive) of an IANA timezone at a given instant.
function tzOffsetMs(atMs, timeZone) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone, hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(atMs));
  const get = (type) => Number(parts.find((p) => p.type === type).value);
  const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'));
  return asUtc - Math.floor(atMs / 1000) * 1000;
}

function validTimeZone(tz) {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return true;
  } catch (_) {
    return false;
  }
}

// UTC ms of local midnight for YYYY-MM-DD in the given timezone.
function localMidnightUtc(dateStr, timeZone) {
  const [y, m, d] = dateStr.split('-').map(Number);
  const guess = Date.UTC(y, m - 1, d);
  return guess - tzOffsetMs(guess, timeZone);
}

function haversineM(a, b) {
  const R = 6371000;
  const toRad = (x) => (x * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

// Distance over a path, ignoring hops shorter than the GPS uncertainty.
function pathDistanceM(fixes) {
  let total = 0;
  for (let i = 1; i < fixes.length; i++) {
    const d = haversineM(fixes[i - 1], fixes[i]);
    if (d > Math.max(fixes[i - 1].accuracy, fixes[i].accuracy)) total += d;
  }
  return total;
}

function formatFix(f) {
  return {
    time: new Date(f.time).toISOString(),
    lat: f.lat,
    lon: f.lon,
    accuracy_m: Math.round(f.accuracy),
    device_id: f.deviceId,
    device: f.deviceLabel,
  };
}

function downsample(rows, max) {
  if (rows.length <= max) return rows;
  const stride = rows.length / max;
  const out = [];
  for (let i = 0; i < max; i++) out.push(rows[Math.floor(i * stride)]);
  if (out[out.length - 1] !== rows[rows.length - 1]) out[out.length - 1] = rows[rows.length - 1];
  return out;
}

const json = (value) => ({ content: [{ type: 'text', text: JSON.stringify(value, null, 2) }] });
const error = (message) => ({ content: [{ type: 'text', text: message }], isError: true });

function registerGoalTools(server, email, canWrite) {
  const resolveGoal = (goalId, goalName) => {
    const all = db.listGoals(email);
    if (goalId !== undefined) return all.find((g) => g.id === goalId) || null;
    if (goalName) {
      const needle = goalName.trim().toLowerCase();
      return all.find((g) => g.name.toLowerCase() === needle) ||
        all.find((g) => g.name.toLowerCase().includes(needle)) || null;
    }
    return null;
  };

  server.registerTool(
    'list_goals',
    {
      title: 'List goals',
      description: "The user's habit goals with progress for the current period: count so far, target, expected-by-now pace, on_track/behind status, and days left.",
      inputSchema: {},
    },
    async () => json(goals.goalsWithProgress(email))
  );

  server.registerTool(
    'get_recent_nudges',
    {
      title: 'Recent nudges',
      description: 'Reminder notifications Claude sent the user for goals they were behind on, newest first, plus whether nudges are enabled.',
      inputSchema: { days: z.number().int().min(1).max(30).default(3) },
    },
    async ({ days }) => json({
      nudges_enabled: db.nudgesEnabled(email),
      nudges: db.nudgesSince(email, Date.now() - days * 86400e3).map((n) => ({
        time: new Date(n.time).toISOString(), goal: n.goal_name, title: n.title, text: n.text, why_sent: n.reason,
      })),
    })
  );

  if (!canWrite) return;

  server.registerTool(
    'log_completion',
    {
      title: 'Log a goal completion',
      description: 'Record that the user did a goal once (e.g. went to the gym). Identify the goal by id or name.',
      inputSchema: {
        goal_id: z.number().int().optional(),
        goal_name: z.string().optional().describe('Case-insensitive; partial match allowed'),
        note: z.string().max(200).optional(),
      },
    },
    async ({ goal_id, goal_name, note }) => {
      const goal = resolveGoal(goal_id, goal_name);
      if (!goal) return error('No matching goal. Use list_goals to see them.');
      const result = goals.logCompletion(email, goal.id, { source: 'mcp', note });
      return json({ logged: true, goal: result.goal });
    }
  );

  server.registerTool(
    'undo_completion',
    {
      title: 'Undo a goal completion',
      description: "Remove the most recent completion of a goal in the current period (e.g. it was logged by mistake).",
      inputSchema: {
        goal_id: z.number().int().optional(),
        goal_name: z.string().optional(),
      },
    },
    async ({ goal_id, goal_name }) => {
      const goal = resolveGoal(goal_id, goal_name);
      if (!goal) return error('No matching goal. Use list_goals to see them.');
      const result = goals.undoCompletion(email, goal.id);
      return json({ removed: Boolean(result.removed), goal: result.goal });
    }
  );

  server.registerTool(
    'create_goal',
    {
      title: 'Create a goal',
      description: 'Add a new habit goal with a target number of completions per period (14 days by default, or 7).',
      inputSchema: {
        name: z.string().min(1).max(60),
        emoji: z.string().max(4).optional(),
        target: z.number().positive().max(500).describe('Completions per period'),
        period_days: z.number().int().optional().describe('7 or 14 (default 14)'),
        hours_offset: z.number().min(0).max(48).optional().describe('Grace hours before each day counts'),
        description: z.string().max(1000).optional().describe('What the goal is and why the user wants it'),
      },
    },
    async (input) => {
      const { fields, errors } = goals.validateGoalInput(input);
      if (errors.length) return error(errors.join('; '));
      const goal = db.createGoal(email, {
        name: fields.name, emoji: fields.emoji, target: fields.target,
        periodDays: fields.period_days, hoursOffset: fields.hours_offset, description: fields.description,
      });
      return json({ created: true, goal: goals.progressForGoal(email, goal.id) });
    }
  );

  server.registerTool(
    'update_goal',
    {
      title: 'Update a goal',
      description: "Change a goal's name, emoji, target, period, grace hours or description (what it is and why the user is doing it — nudges use this). Only the fields given change.",
      inputSchema: {
        goal_id: z.number().int().optional(),
        goal_name: z.string().optional().describe('Case-insensitive name match, if goal_id is not given'),
        name: z.string().min(1).max(60).optional(),
        emoji: z.string().max(4).optional(),
        target: z.number().positive().max(500).optional(),
        period_days: z.number().int().optional().describe('7 or 14'),
        hours_offset: z.number().min(0).max(48).optional(),
        description: z.string().max(1000).optional(),
      },
    },
    async ({ goal_id, goal_name, ...changes }) => {
      const goal = resolveGoal(goal_id, goal_name);
      if (!goal) return error('No matching goal. Use list_goals to see them.');
      const { fields, errors } = goals.validateGoalInput(changes, { partial: true });
      if (errors.length) return error(errors.join('; '));
      db.updateGoal(email, goal.id, fields);
      return json({ updated: true, goal: goals.progressForGoal(email, goal.id) });
    }
  );

  server.registerTool(
    'set_nudges_enabled',
    {
      title: 'Turn nudges on or off',
      description: 'Enable or disable the hourly Claude nudge notifications for goals the user is behind on.',
      inputSchema: { enabled: z.boolean() },
    },
    async ({ enabled }) => {
      db.setNudgesEnabled(email, enabled);
      return json({ nudges_enabled: enabled });
    }
  );
}

// One-call "how am I doing" snapshot; includes whatever the grant's scopes allow.
function registerOverviewTool(server, email, allowed) {
  server.registerTool(
    'get_overview',
    {
      title: 'Overview',
      description: "A quick snapshot of the user's day: where they are in the current goal period, each goal's progress and pace, and (if permitted) their latest location. A good first call.",
      inputSchema: {},
    },
    async () => {
      const out = { now: new Date().toISOString() };
      if (allowed.goals) {
        const data = goals.goalsWithProgress(email);
        const p = data.periods[14] || Object.values(data.periods)[0];
        out.timezone = data.timezone;
        if (p) out.period = { start: p.startDate, end: p.endDate, day: p.dayOfPeriod + 1, of_days: p.periodDays, days_left: p.daysLeft };
        out.goals = data.goals.map((g) => ({
          name: g.name, emoji: g.emoji, description: g.description, done: g.count, target: g.target,
          expected_by_now: g.target_by_now, status: g.count >= g.target ? 'complete' : g.status,
          period_days: g.period_days,
        }));
        out.summary = {
          on_track: out.goals.filter((g) => g.status !== 'behind').length,
          behind: out.goals.filter((g) => g.status === 'behind').map((g) => g.name),
        };
      }
      if (allowed.location) {
        const fix = db.latestFix(email);
        out.latest_location = fix
          ? { ...formatFix(fix), age_minutes: Math.round((Date.now() - fix.time) / 60000) }
          : null;
      }
      return json(out);
    }
  );
}

// --- Phone control (phone:control): a fixed command list the app executes -----

const PHONE_COMMANDS = {
  diagnostics: 'Full health report: app version, tracking/service state, last fix age, unsynced counts, sync errors, power state (Doze, battery saver, battery-optimisation exemption, standby bucket, background restriction), every permission, recent process deaths with reasons, recent tracking events, App Functions registration, goals/widget cache age.',
  restart_tracking: 'Re-register location updates in the tracking service, or start it if it is not running.',
  sync_now: 'Upload any unsynced fixes and tracking events now.',
  fresh_fix: 'Take one high-accuracy location fix now (up to ~30 s) and upload it.',
  refresh_widget: 'Re-fetch goals from the server and redraw the home-screen widget.',
  notify: 'Show a notification on the phone (args: title, text, optional open = one of settings targets) — use it to ask the user to fix a permission or setting.',
};
const NOTIFY_OPEN_TARGETS = ['app', 'app_settings', 'battery_optimization', 'exact_alarms', 'notification_settings', 'overlay'];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function formatCommand(c) {
  return {
    command_id: c.id,
    device_id: c.device_id,
    command: c.command,
    status: c.status,
    requested_at: new Date(c.created_at).toISOString(),
    completed_at: c.completed_at ? new Date(c.completed_at).toISOString() : null,
    result: c.result,
  };
}

function registerPhoneTools(server, email, clientName) {
  // Queues a command for the phone and waits (up to wait_seconds) for its result.
  const runCommand = async (command, args, deviceId, waitSeconds) => {
    const device = deviceId != null ? db.deviceForEmail(email, deviceId) : db.mostRecentDevice(email);
    if (!device) return error('No linked phone found (see list_devices).');
    const id = db.queuePhoneCommand(device.id, command, args, clientName);
    const deadline = Date.now() + waitSeconds * 1000;
    let row = db.getPhoneCommand(email, id);
    while (Date.now() < deadline && (row.status === 'pending' || row.status === 'delivered')) {
      await sleep(1000);
      row = db.getPhoneCommand(email, id);
    }
    const out = formatCommand(row);
    if (row.status === 'pending' || row.status === 'delivered') {
      out.note = `The phone has not ${row.status === 'pending' ? 'picked it up' : 'finished it'} yet; call get_phone_command with command_id ${id} later.`;
    }
    return json(out);
  };

  server.registerTool(
    'phone_command',
    {
      title: 'Run a command on the phone',
      description: 'Queue one of a fixed set of commands for the Starchart app on the phone and wait for its result. '
        + 'The app picks commands up when it next talks to the server: within about a minute while tracking is running, '
        + 'up to ~10 minutes if the phone is in deep Doze. If it has not answered within wait_seconds the command stays '
        + 'queued (for an hour) — check it later with get_phone_command. Commands: '
        + Object.entries(PHONE_COMMANDS).map(([k, v]) => `${k} — ${v}`).join(' '),
      inputSchema: {
        command: z.enum(Object.keys(PHONE_COMMANDS)),
        title: z.string().max(80).optional().describe('notify only'),
        text: z.string().max(400).optional().describe('notify only'),
        open: z.enum(NOTIFY_OPEN_TARGETS).optional().describe('notify only: screen to open when the notification is tapped'),
        device_id: z.number().int().optional().describe('Defaults to the phone that most recently sent a fix'),
        wait_seconds: z.number().int().min(0).max(55).default(45),
      },
    },
    async ({ command, title, text, open, device_id, wait_seconds }) => {
      let args = {};
      if (command === 'notify') {
        if (!title && !text) return error('notify needs a title or text');
        args = { title: title || 'Starchart', text: text || '', open: open || 'app' };
      }
      return runCommand(command, args, device_id, wait_seconds);
    }
  );

  server.registerTool(
    'send_notification',
    {
      title: 'Send the user a notification',
      description: "Show a notification with any text on the user's phone (through the Starchart app). "
        + 'Tapping it opens Starchart, or the settings screen given in open. Delivery takes up to a minute while the phone '
        + 'is awake, longer in deep Doze; the result says whether it was shown.',
      inputSchema: {
        title: z.string().min(1).max(80),
        text: z.string().max(400).default(''),
        open: z.enum(NOTIFY_OPEN_TARGETS).default('app'),
        wait_seconds: z.number().int().min(0).max(55).default(30),
      },
    },
    async ({ title, text, open, wait_seconds }) => runCommand('notify', { title, text, open }, null, wait_seconds)
  );

  server.registerTool(
    'get_phone_command',
    {
      title: 'Phone command status',
      description: 'Status and result of a command queued with phone_command. Omit command_id to list the 20 most recent commands.',
      inputSchema: { command_id: z.number().int().optional() },
    },
    async ({ command_id }) => {
      if (command_id == null) {
        return json({
          commands: db.listPhoneCommands(email).map((c) => ({
            command_id: c.id, device_id: c.device_id, command: c.command, status: c.status,
            requested_by: c.requested_by, requested_at: new Date(c.created_at).toISOString(),
          })),
        });
      }
      const row = db.getPhoneCommand(email, command_id);
      if (!row) return error('No such command.');
      return json(formatCommand(row));
    }
  );
}

function buildServer(email, scopes, clientName) {
  const server = new McpServer({ name: 'starchart', version: '1.2.0' });
  const has = (s) => scopes.includes(s);

  if (has('phone:control')) registerPhoneTools(server, email, clientName);

  registerOverviewTool(server, email, { goals: has('goals:read'), location: has('location:read') });
  if (has('goals:read')) registerGoalTools(server, email, has('goals:write'));
  if (!has('location:read')) return server;

  server.registerTool(
    'get_latest_location',
    {
      title: 'Latest location',
      description: "The most recent location fix from any of the user's phones.",
      inputSchema: {},
    },
    async () => {
      const fix = db.latestFix(email);
      if (!fix) return json({ latest: null, note: 'No location fixes recorded yet.' });
      return json({
        latest: formatFix(fix),
        age_minutes: Math.round((Date.now() - fix.time) / 60000),
      });
    }
  );

  server.registerTool(
    'list_devices',
    {
      title: 'List devices',
      description: 'Phones linked to this account, with fix counts and last fix time.',
      inputSchema: {},
    },
    async () => json({
      devices: db.listDevices(email).map((d) => ({
        device_id: d.id,
        label: d.label,
        fix_count: d.fix_count,
        last_fix: d.last_fix_time ? new Date(d.last_fix_time).toISOString() : null,
      })),
    })
  );

  server.registerTool(
    'list_days_with_data',
    {
      title: 'Days with location data',
      description: 'Calendar days (in the given timezone) that have at least one location fix, with counts.',
      inputSchema: {
        timezone: z.string().default('UTC').describe('IANA timezone, e.g. Europe/London'),
      },
    },
    async ({ timezone }) => {
      if (!validTimeZone(timezone)) return error(`Unknown timezone: ${timezone}`);
      const offsetMinutes = Math.round(tzOffsetMs(Date.now(), timezone) / 60000);
      return json({ timezone, days: db.daysWithFixes(email, offsetMinutes) });
    }
  );

  server.registerTool(
    'get_day_summary',
    {
      title: 'Day summary',
      description: 'Summary of one calendar day: fix count, first/last time, distance travelled, bounding box, and a coarse hourly sample of positions.',
      inputSchema: {
        date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).describe('YYYY-MM-DD'),
        timezone: z.string().default('UTC').describe('IANA timezone the day is measured in'),
      },
    },
    async ({ date, timezone }) => {
      if (!validTimeZone(timezone)) return error(`Unknown timezone: ${timezone}`);
      const from = localMidnightUtc(date, timezone);
      const [y, m, d] = date.split('-').map(Number);
      const nextDay = new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
      const to = localMidnightUtc(nextDay, timezone);
      const all = db.fixesBetween(email, from, to);
      const good = all.filter((f) => f.accuracy <= 250);
      if (all.length === 0) return json({ date, timezone, fixes: 0 });
      const used = good.length ? good : all;
      const lats = used.map((f) => f.lat);
      const lons = used.map((f) => f.lon);
      return json({
        date,
        timezone,
        fixes: all.length,
        low_accuracy_fixes_excluded: all.length - good.length,
        first: new Date(used[0].time).toISOString(),
        last: new Date(used[used.length - 1].time).toISOString(),
        distance_km: Math.round(pathDistanceM(used) / 100) / 10,
        bounding_box: {
          south: Math.min(...lats), north: Math.max(...lats),
          west: Math.min(...lons), east: Math.max(...lons),
        },
        hourly_sample: downsample(used, 24).map(formatFix),
      });
    }
  );

  server.registerTool(
    'get_location_history',
    {
      title: 'Location history',
      description: 'Location fixes in a time range (max 31 days), oldest first, evenly downsampled to max_points.',
      inputSchema: {
        from: z.string().describe('ISO 8601 start, inclusive'),
        to: z.string().describe('ISO 8601 end, exclusive'),
        device_id: z.number().int().optional().describe('Restrict to one device (see list_devices)'),
        max_points: z.number().int().min(1).max(2000).default(500),
        min_accuracy_m: z.number().min(0).default(250).describe('Drop fixes less accurate than this'),
      },
    },
    async ({ from, to, device_id, max_points, min_accuracy_m }) => {
      const fromMs = Date.parse(from);
      const toMs = Date.parse(to);
      if (!Number.isFinite(fromMs) || !Number.isFinite(toMs)) return error('from/to must be ISO 8601 timestamps');
      if (toMs <= fromMs) return error('to must be after from');
      if (toMs - fromMs > MAX_RANGE_MS) return error('range must be at most 31 days');
      const rows = db.fixesBetween(email, fromMs, toMs, device_id ?? null)
        .filter((f) => f.accuracy <= min_accuracy_m);
      const sampled = downsample(rows, max_points);
      return json({
        from: new Date(fromMs).toISOString(),
        to: new Date(toMs).toISOString(),
        total_fixes: rows.length,
        returned: sampled.length,
        distance_km: Math.round(pathDistanceM(rows) / 100) / 10,
        fixes: sampled.map(formatFix),
      });
    }
  );

  server.registerTool(
    'find_location_gaps',
    {
      title: 'Find gaps in location tracking',
      description: "Periods (max 31-day range) where the phone recorded no location fixes for longer than min_gap_minutes, each with the distance moved across it and the phone's tracking diagnostics logged in and around it (service starts/stops, process deaths with Android's exit reason, watchdog restarts, power state). Use to explain why tracking stopped.",
      inputSchema: {
        from: z.string().describe('ISO 8601 start, inclusive'),
        to: z.string().describe('ISO 8601 end, exclusive'),
        min_gap_minutes: z.number().min(1).default(15),
      },
    },
    async ({ from, to, min_gap_minutes }) => {
      const fromMs = Date.parse(from);
      const toMs = Date.parse(to);
      if (!Number.isFinite(fromMs) || !Number.isFinite(toMs)) return error('from/to must be ISO 8601 timestamps');
      if (toMs <= fromMs) return error('to must be after from');
      if (toMs - fromMs > MAX_RANGE_MS) return error('range must be at most 31 days');
      const fixes = db.fixesBetween(email, fromMs, toMs);
      const events = db.trackingEventsBetween(email, fromMs, toMs);
      const minGapMs = min_gap_minutes * 60000;
      const formatEvent = (e) => ({ time: new Date(e.time).toISOString(), kind: e.kind, detail: e.detail, device_id: e.deviceId });
      // Events a few minutes either side matter too: the death that caused a
      // gap is often logged by the process that starts after it.
      const slackMs = 10 * 60000;
      const gaps = [];
      for (let i = 1; i < fixes.length; i++) {
        const a = fixes[i - 1];
        const b = fixes[i];
        if (b.time - a.time < minGapMs) continue;
        gaps.push({
          start: new Date(a.time).toISOString(),
          end: new Date(b.time).toISOString(),
          minutes: Math.round((b.time - a.time) / 60000),
          moved_m: Math.round(haversineM(a, b)),
          events: events.filter((e) => e.time >= a.time - slackMs && e.time <= b.time + slackMs).map(formatEvent),
        });
      }
      return json({
        from: new Date(fromMs).toISOString(),
        to: new Date(toMs).toISOString(),
        fixes: fixes.length,
        first_fix: fixes.length ? new Date(fixes[0].time).toISOString() : null,
        last_fix: fixes.length ? new Date(fixes[fixes.length - 1].time).toISOString() : null,
        diagnostics_logged: events.length,
        gaps,
      });
    }
  );

  return server;
}

// Some MCP clients (notably the Gemini SDK) crash on boolean sub-schemas such as
// `additionalProperties: false`. Arguments are still validated server-side
// against the zod schemas, so the listed schemas can safely drop them.
function clientFriendlySchema(schema) {
  if (Array.isArray(schema)) return schema.map(clientFriendlySchema);
  if (!schema || typeof schema !== 'object') return schema;
  const out = {};
  for (const [key, value] of Object.entries(schema)) {
    if (key === '$schema') continue;
    if (typeof value === 'boolean' && (key === 'additionalProperties' || key === 'items')) continue;
    out[key] = clientFriendlySchema(value);
  }
  return out;
}

function simplifyListedSchemas(server) {
  const handlers = server.server._requestHandlers;
  const original = handlers && handlers.get('tools/list');
  if (!original) return;
  handlers.set('tools/list', async (request, extra) => {
    const result = await original(request, extra);
    for (const tool of result.tools || []) {
      tool.inputSchema = clientFriendlySchema(tool.inputSchema);
      if (tool.outputSchema) tool.outputSchema = clientFriendlySchema(tool.outputSchema);
    }
    return result;
  });
}

// One server + transport per request: stateless, nothing to leak between
// users, and it scales to zero with the Fly machine.
async function handleMcpRequest(req, res) {
  const server = buildServer(req.grant.email, req.grant.scope.split(' '), req.grant.client_name);
  simplifyListedSchemas(server);
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  res.on('close', () => {
    transport.close();
    server.close();
  });
  await server.connect(transport);
  await transport.handleRequest(req, res, req.body);
}

module.exports = { handleMcpRequest, buildServer, simplifyListedSchemas };
