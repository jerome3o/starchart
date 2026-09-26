'use strict';

// MCP server (Streamable HTTP, stateless) exposing the signed-in user's
// location data. Every tool is scoped to the email on the bearer token's grant.

const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const { StreamableHTTPServerTransport } = require('@modelcontextprotocol/sdk/server/streamableHttp.js');
const { z } = require('zod');
const db = require('./db');

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

function buildServer(email) {
  const server = new McpServer({ name: 'starchart', version: '1.0.0' });

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

  return server;
}

// One server + transport per request: stateless, nothing to leak between
// users, and it scales to zero with the Fly machine.
async function handleMcpRequest(req, res) {
  const server = buildServer(req.grant.email);
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  res.on('close', () => {
    transport.close();
    server.close();
  });
  await server.connect(transport);
  await transport.handleRequest(req, res, req.body);
}

module.exports = { handleMcpRequest };
