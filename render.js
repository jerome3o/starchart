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

const FONT_DIR = path.join(__dirname, 'fonts');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'DejaVuSans.ttf'), 'DejaVu');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'DejaVuSans-Bold.ttf'), 'DejaVu');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'NotoEmoji-Regular.woff2'), 'NotoEmoji');
GlobalFonts.registerFromPath(path.join(FONT_DIR, 'NotoSansSC-Regular.ttf'), 'NotoSansSC');
const FAMILY = 'DejaVu, NotoEmoji, NotoSansSC, sans-serif';

const WIDTH = 800;
const HEIGHT = 480;
const X_MARGIN = 20;
const HEADER_LINE_Y = 56;
const GOALS_TOP = HEADER_LINE_Y + 10;
const FOOTER_HEIGHT = 26; // day ticks + weekday initials
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WEEKDAY_INITIAL = { Sun: 'S', Mon: 'M', Tue: 'T', Wed: 'W', Thu: 'T', Fri: 'F', Sat: 'S' };

function fmtDate(ymd, withYear) {
  const [y, m, d] = ymd.split('-').map(Number);
  return `${MONTHS[m - 1]} ${d}${withYear ? `, ${y}` : ''}`;
}

function fmtNumber(n) {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

// Everything is drawn with integer-pixel rectangles so nothing depends on
// anti-aliasing surviving the 1-bit conversion.
function rect(ctx, x, y, w, h, color = '#000') {
  if (w <= 0 || h <= 0) return;
  ctx.fillStyle = color;
  ctx.fillRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h));
}

function outline(ctx, x, y, w, h, t) {
  rect(ctx, x, y, w, t);
  rect(ctx, x, y + h - t, w, t);
  rect(ctx, x, y, t, h);
  rect(ctx, x + w - t, y, t, h);
}

function hatch(ctx, x0, y0, x1, y1, step = 4) {
  ctx.fillStyle = '#000';
  for (let y = y0; y < y1; y += step) {
    const shift = (Math.floor(y / step) % 2) * (step / 2);
    for (let x = x0 + shift; x < x1; x += step) ctx.fillRect(x, y, 1, 1);
  }
}

// Text drawn on an alphabetic baseline from the primary font's metrics, so
// emoji/CJK fallback glyphs (taller ascents) can't push a label down.
function text(ctx, str, x, top, size, { bold = false, align = 'left' } = {}) {
  ctx.font = `${bold ? 'bold ' : ''}${size}px ${FAMILY}`;
  ctx.textBaseline = 'alphabetic';
  ctx.fillStyle = '#000';
  const w = ctx.measureText(str).width;
  const baseline = top + Math.round(size * 0.8);
  ctx.fillText(str, align === 'right' ? x - w : x, baseline);
  return w;
}

function fitText(ctx, str, maxWidth, size, bold) {
  ctx.font = `${bold ? 'bold ' : ''}${size}px ${FAMILY}`;
  if (ctx.measureText(str).width <= maxWidth) return str;
  const chars = [...str];
  while (chars.length > 1 && ctx.measureText(`${chars.join('')}…`).width > maxWidth) chars.pop();
  return `${chars.join('').trimEnd()}…`;
}

function drawProgressBar(ctx, { x, y, width, height, count, target, hoursOffset, periodDays }) {
  const border = 2;
  const innerX = x + border;
  const innerW = width - border * 2;
  const innerY = y + border;
  const innerH = height - border * 2;

  // Grace period: a hatched block at the start that counts as "free" time.
  const graceW = Math.min(innerW, Math.round(((hoursOffset / 24) / periodDays) * innerW));
  const filledW = Math.min(innerW - graceW, Math.round(Math.min(count / target, 1) * innerW));
  rect(ctx, innerX, innerY, innerW, innerH, '#fff');
  if (graceW > 0) hatch(ctx, innerX, innerY, innerX + graceW, innerY + innerH, 3);
  rect(ctx, innerX + graceW, innerY, filledW, innerH);

  // Segment dividers for whole-number targets (every 5th when there are many).
  if (Number.isInteger(target) && target > 1) {
    const every = target > 30 ? 5 : 1;
    for (let i = every; i < target; i += every) {
      const sx = innerX + Math.round((i / target) * innerW);
      const inFill = sx < innerX + graceW + filledW;
      if (inFill) rect(ctx, sx - 1, innerY, 2, innerH, '#fff');
      else rect(ctx, sx, innerY, 1, innerH);
    }
  }
  outline(ctx, x, y, width, height, border);
}

// Plain data in, canvas out: `data` is goalsWithProgress()'s shape.
function drawDashboardData(data, nowMs) {
  const canvas = createCanvas(WIDTH, HEIGHT);
  const ctx = canvas.getContext('2d');
  rect(ctx, 0, 0, WIDTH, HEIGHT, '#fff');

  const barWidth = WIDTH - X_MARGIN * 2;
  // The timeline follows the longest period in use (14 days normally).
  const periodDays = Math.max(14, ...data.goals.map((g) => g.period_days));
  const period = goals.currentPeriod(nowMs, data.timezone, periodDays);
  const dayX = (d) => X_MARGIN + Math.round((d / periodDays) * barWidth);
  const [sy, sm, sd] = period.startDate.split('-').map(Number);
  const weekdayOf = (d) => time.localParts(
    time.localMidnightUtc(...time.addDays(sy, sm, sd, d), data.timezone) + 12 * 3600e3, data.timezone
  ).weekday;
  const footerTop = HEIGHT - FOOTER_HEIGHT;

  // Weekend band behind the goals.
  for (let d = 0; d < periodDays; d++) {
    const wd = weekdayOf(d);
    if (wd === 'Sat' || wd === 'Sun') hatch(ctx, dayX(d), HEADER_LINE_Y + 4, dayX(d + 1), footerTop);
  }

  // Header
  text(ctx, `${fmtDate(period.startDate, false)} – ${fmtDate(period.endDate, true)}`, X_MARGIN, 8, 24, { bold: true });
  text(ctx, `Day ${period.dayOfPeriod + 1} of ${periodDays} · ${period.weekday}`, X_MARGIN, 36, 16);
  const p = time.localParts(nowMs, data.timezone);
  text(ctx, `Updated ${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`,
    WIDTH - X_MARGIN, 36, 16, { align: 'right' });
  rect(ctx, X_MARGIN, HEADER_LINE_Y - 2, barWidth, 2);


  // Goal rows scale with how many there are: roomy for a few, compact for many.
  const n = data.goals.length;
  const rowH = n ? (footerTop - GOALS_TOP) / n : 0;
  const labelSize = Math.max(13, Math.min(22, Math.floor(rowH * 0.32)));
  const barH = Math.max(10, Math.min(26, Math.floor(rowH * 0.34)));
  const gap = Math.max(3, Math.min(8, Math.floor(rowH * 0.08)));
  const blockH = labelSize + gap + barH;

  const labelBoxes = [];
  if (data.goals.length === 0) {
    const msg = 'No goals yet — add some in the Starchart app.';
    ctx.font = `22px ${FAMILY}`;
    const box = [X_MARGIN - 4, GOALS_TOP + 26, ctx.measureText(msg).width + 10, 30];
    rect(ctx, ...box, '#fff');
    text(ctx, msg, X_MARGIN, GOALS_TOP + 30, 22);
    labelBoxes.push(box);
  }
  data.goals.forEach((g, i) => {
    const rowTop = GOALS_TOP + Math.round(i * rowH);
    const top = rowTop + Math.max(0, Math.floor((rowH - blockH) / 2));

    // Count on the right ("3/8", "+2" when over target), name truncated to fit.
    const over = g.count > g.target ? `  +${fmtNumber(g.count - g.target)}` : '';
    const countStr = `${g.count}/${fmtNumber(g.target)}${over}`;
    ctx.font = `bold ${labelSize}px ${FAMILY}`;
    const countW = ctx.measureText(countStr).width;
    // A white plate keeps labels readable over the weekend hatch.
    rect(ctx, WIDTH - X_MARGIN - countW - 6, top - 2, countW + 6, labelSize + 4, '#fff');
    labelBoxes.push([WIDTH - X_MARGIN - countW - 6, top - 2, countW + 6, labelSize + 4]);
    text(ctx, countStr, WIDTH - X_MARGIN, top, labelSize, { bold: true, align: 'right' });
    const label = fitText(ctx, g.emoji ? `${g.emoji} ${g.name}` : g.name, barWidth - countW - 24, labelSize, false);
    ctx.font = `${labelSize}px ${FAMILY}`;
    const labelW = ctx.measureText(label).width + 8;
    rect(ctx, X_MARGIN - 2, top - 2, labelW, labelSize + 4, '#fff');
    labelBoxes.push([X_MARGIN - 2, top - 2, labelW, labelSize + 4]);
    text(ctx, label, X_MARGIN, top, labelSize);

    // A shorter-period goal (7 days on the 14-day axis) sits in the slice of
    // the timeline that is its current period, so the "now" line applies.
    const own = goals.currentPeriod(nowMs, data.timezone, g.period_days);
    const startDay = period.dayOfPeriod - own.dayOfPeriod;
    const barX = dayX(startDay);
    const barW = dayX(startDay + g.period_days) - barX;
    drawProgressBar(ctx, {
      x: barX, y: top + labelSize + gap, width: barW, height: barH,
      count: g.count, target: g.target, hoursOffset: g.hours_offset, periodDays: g.period_days,
    });
  });

  // "Now": a dashed line with a white halo so it stays visible over filled bars.
  const nowX = Math.max(X_MARGIN + 2, Math.min(X_MARGIN + Math.round(period.fraction * barWidth), WIDTH - X_MARGIN - 3));
  const underLabel = (y) => labelBoxes.some(([bx, by, bw, bh]) =>
    nowX + 4 > bx && nowX - 2 < bx + bw && y + 6 > by && y < by + bh);
  for (let y = HEADER_LINE_Y + 2; y < footerTop; y += 8) {
    if (underLabel(y)) continue;
    rect(ctx, nowX - 2, y, 6, 6, '#fff');
    rect(ctx, nowX, y + 1, 2, 4);
  }

  // Footer: day ticks and weekday initials.
  rect(ctx, X_MARGIN, footerTop + 1, barWidth, 1);
  for (let d = 0; d <= periodDays; d++) {
    const x = Math.min(dayX(d), WIDTH - X_MARGIN - 2);
    const major = d % 7 === 0;
    rect(ctx, x, footerTop + 1, 2, major ? 8 : 5);
  }
  for (let d = 0; d < periodDays; d++) {
    const cx = (dayX(d) + dayX(d + 1)) / 2;
    ctx.font = `12px ${FAMILY}`;
    const letter = WEEKDAY_INITIAL[weekdayOf(d)];
    const isToday = d === period.dayOfPeriod;
    text(ctx, letter, cx - ctx.measureText(letter).width / 2, footerTop + 10, 13, { bold: isToday });
    if (isToday) rect(ctx, cx - 5, HEIGHT - 3, 10, 2);
  }
  return canvas;
}

function drawDashboard(email, nowMs = Date.now()) {
  return drawDashboardData(goals.goalsWithProgress(email, nowMs), nowMs);
}

// --- 1-bit PNG encoding ---------------------------------

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
      // Straight threshold: anti-aliased edges snap to black/white instead of
      // turning into speckle under dithering.
      // Biased towards black so thin emoji/CJK strokes survive at small sizes.
      if (lum > 190) raw[rowStart + 1 + (x >> 3)] |= 0x80 >> (x & 7); // 1 = white
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

module.exports = { drawDashboard, drawDashboardData, renderDashboardPng, canvasToPng1bit, WIDTH, HEIGHT };
