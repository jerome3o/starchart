'use strict';

// Renders the goals dashboard for 800x480 1-bit e-ink displays. A port of the
// original TRMNL dashboard: header with period + day, one full-width
// segmented progress bar per goal, a shaded weekend band, a dashed "now"
// line, and day ticks. Output is a true 1-bit grayscale PNG.

const path = require('path');
const zlib = require('zlib');
const { createCanvas, GlobalFonts } = require('@napi-rs/canvas');
const goals = require('./goals');
const time = require('./time');
const db = require('./db');

const FONT_DIR = path.join(__dirname, 'fonts');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'DejaVuSans.ttf'), 'DejaVu');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'DejaVuSans-Bold.ttf'), 'DejaVu');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'NotoEmoji-Regular.woff2'), 'NotoEmoji');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'NotoSansSC-Regular.ttf'), 'NotoSansSC');
const FAMILY = 'DejaVu, NotoEmoji, NotoSansSC, sans-serif';

const WIDTH = 800;
const HEIGHT = 480;
const X_MARGIN = 20;
const HEADER_HEIGHT = 62;
const HEADER_LINE_Y = 52;
const BOTTOM_MARGIN = 20;
const BAR_HEIGHT = 20;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function fmtDate(ymd, withYear) {
  const [y, m, d] = ymd.split('-').map(Number);
  return `${MONTHS[m - 1]} ${String(d).padStart(2, '0')}${withYear ? `, ${y}` : ''}`;
}

function drawDashedVertical(ctx, x, top, bottom) {
  ctx.fillStyle = '#000';
  for (let y = top; y < bottom; y += 7) ctx.fillRect(x - 1, y, 2, Math.min(4, bottom - y));
}

function drawProgressBar(ctx, { x, y, width, height, count, target, hoursOffset, periodDays }) {
  if (target <= 0) return;
  const isInteger = Number.isInteger(target);
  const offsetWidth = Math.floor(((hoursOffset / 24) / periodDays) * width);
  const progressWidth = Math.floor(Math.min(count / target, 1) * width);
  const filled = Math.min(offsetWidth + progressWidth, width);

  if (filled > 0) {
    ctx.fillStyle = '#000';
    ctx.fillRect(x, y, filled, height);
  }
  if (isInteger) {
    const segment = width / target;
    for (let i = 1; i < target; i++) {
      const sx = x + Math.floor(i * segment);
      if (sx < x + filled) {
        ctx.fillStyle = '#fff';
        ctx.fillRect(sx - 1, y, 2, height);
      } else {
        ctx.fillStyle = '#000';
        ctx.fillRect(sx, y, 1, height);
      }
    }
  }
  ctx.strokeStyle = '#000';
  ctx.lineWidth = 1;
  ctx.strokeRect(x + 0.5, y + 0.5, width - 1, height - 1);
}

// Draws the dashboard for a user; returns the canvas.
function drawDashboard(email, nowMs = Date.now()) {
  const data = goals.goalsWithProgress(email, nowMs);
  const canvas = createCanvas(WIDTH, HEIGHT);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, WIDTH, HEIGHT);
  ctx.textBaseline = 'top';

  const barWidth = WIDTH - X_MARGIN * 2;
  // The bar timeline follows the longest period in use (14 days normally).
  const periodDays = Math.max(14, ...data.goals.map((g) => g.period_days));
  const period = goals.currentPeriod(nowMs, data.timezone, periodDays);

  // Weekend band behind everything (interior Saturdays/Sundays only).
  ctx.fillStyle = '#d0d0d0';
  for (let d = 1; d < periodDays - 1; d++) {
    const [y, m, dd] = period.startDate.split('-').map(Number);
    const dayMs = time.localMidnightUtc(...time.addDays(y, m, dd, d), data.timezone) + 12 * 3600e3;
    const weekday = time.localParts(dayMs, data.timezone).weekday;
    if (weekday === 'Sat' || weekday === 'Sun') {
      const x0 = X_MARGIN + Math.floor((d / periodDays) * barWidth);
      const x1 = X_MARGIN + Math.floor(((d + 1) / periodDays) * barWidth);
      ctx.fillRect(x0, HEADER_LINE_Y, x1 - x0, HEIGHT - HEADER_LINE_Y);
    }
  }

  // Header
  ctx.fillStyle = '#000';
  ctx.font = `20px ${FAMILY}`;
  ctx.fillText(`${fmtDate(period.startDate, false)} - ${fmtDate(period.endDate, true)}`, X_MARGIN, 10);
  ctx.font = `14px ${FAMILY}`;
  ctx.fillText(`Day ${period.dayOfPeriod + 1} of ${periodDays} (${period.weekday})`, X_MARGIN, 32);
  const p = time.localParts(nowMs, data.timezone);
  const updated = `Updated: ${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
  ctx.fillText(updated, WIDTH - X_MARGIN - ctx.measureText(updated).width, 32);
  ctx.fillRect(X_MARGIN, HEADER_LINE_Y - 1, barWidth, 2);

  // Goals
  let goalsBottom = null;
  if (data.goals.length === 0) {
    ctx.font = `18px ${FAMILY}`;
    ctx.fillText('No goals yet — add some in the Starchart app.', X_MARGIN, HEADER_HEIGHT + 20);
  } else {
    const available = HEIGHT - HEADER_HEIGHT - BOTTOM_MARGIN;
    const spacing = available / data.goals.length;
    data.goals.forEach((g, i) => {
      const y = HEADER_HEIGHT + Math.floor(i * spacing);
      ctx.fillStyle = '#000';
      ctx.font = `16px ${FAMILY}`;
      ctx.fillText(g.emoji ? `${g.emoji} ${g.name}` : g.name, X_MARGIN, y);
      const countText = `${g.count}/${Number.isInteger(g.target) ? g.target : g.target.toFixed(1)}`;
      ctx.font = `bold 16px ${FAMILY}`;
      ctx.fillText(countText, WIDTH - X_MARGIN - ctx.measureText(countText).width, y);
      const barY = y + 22;
      // A shorter-period goal (7 days on a 14-day axis) occupies the slice of
      // the timeline that is its current period, so the "now" line still applies.
      const own = goals.currentPeriod(nowMs, data.timezone, g.period_days);
      const offsetDays = period.dayOfPeriod - own.dayOfPeriod;
      const barX = X_MARGIN + Math.floor((offsetDays / periodDays) * barWidth);
      const ownWidth = Math.floor((g.period_days / periodDays) * barWidth);
      drawProgressBar(ctx, {
        x: barX, y: barY, width: ownWidth, height: BAR_HEIGHT,
        count: g.count, target: g.target, hoursOffset: g.hours_offset, periodDays: g.period_days,
      });
      goalsBottom = barY + BAR_HEIGHT;
    });
  }

  // "Now" line and day ticks
  const nowX = Math.max(X_MARGIN + 2, Math.min(X_MARGIN + Math.floor(period.fraction * barWidth), WIDTH - X_MARGIN - 2));
  drawDashedVertical(ctx, nowX, HEADER_LINE_Y, HEIGHT);
  if (goalsBottom !== null) {
    ctx.fillStyle = '#000';
    for (let d = 0; d <= periodDays; d++) {
      const x = X_MARGIN + Math.floor((d / periodDays) * barWidth);
      const long = d === 0 || d === periodDays || d % 7 === 0 || d % 7 === 6;
      ctx.fillRect(Math.min(x, WIDTH - X_MARGIN - 2), goalsBottom + 3, 2, long ? 7 : 5);
    }
  }
  return canvas;
}

// --- 1-bit PNG encoding with ordered dithering ---------------------------------

const BAYER4 = [
  [0, 8, 2, 10],
  [12, 4, 14, 6],
  [3, 11, 1, 9],
  [15, 7, 13, 5],
];

const CRC_TABLE = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function canvasToPng1bit(canvas) {
  const { width, height } = canvas;
  const rgba = canvas.getContext('2d').getImageData(0, 0, width, height).data;
  const rowBytes = Math.ceil(width / 8);
  const raw = Buffer.alloc((rowBytes + 1) * height);
  for (let y = 0; y < height; y++) {
    const rowStart = y * (rowBytes + 1);
    raw[rowStart] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const lum = 0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2];
      const threshold = ((BAYER4[y & 3][x & 3] + 0.5) / 16) * 255;
      if (lum > threshold) raw[rowStart + 1 + (x >> 3)] |= 0x80 >> (x & 7); // 1 = white
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 1; // bit depth
  ihdr[9] = 0; // grayscale
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function renderDashboardPng(email, nowMs = Date.now()) {
  return canvasToPng1bit(drawDashboard(email, nowMs));
}

module.exports = { drawDashboard, renderDashboardPng, canvasToPng1bit, WIDTH, HEIGHT };
