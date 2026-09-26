'use strict';

// Timezone helpers built on Intl, so no tz database dependency.

function validTimeZone(tz) {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return true;
  } catch (_) {
    return false;
  }
}

// Local wall-clock parts of an instant in a timezone.
function localParts(atMs, timeZone) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone, hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'short',
  }).formatToParts(new Date(atMs));
  const get = (type) => parts.find((p) => p.type === type).value;
  return {
    year: Number(get('year')), month: Number(get('month')), day: Number(get('day')),
    hour: Number(get('hour')), minute: Number(get('minute')), second: Number(get('second')),
    weekday: get('weekday'),
  };
}

// Offset (ms, east positive) of a timezone at an instant.
function tzOffsetMs(atMs, timeZone) {
  const p = localParts(atMs, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - Math.floor(atMs / 1000) * 1000;
}

// UTC ms of local midnight for a calendar date (y, m, d) in a timezone.
function localMidnightUtc(year, month, day, timeZone) {
  const guess = Date.UTC(year, month - 1, day);
  return guess - tzOffsetMs(guess, timeZone);
}

function parseYmd(dateStr) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

// Whole days between two calendar dates, ignoring DST.
function daysBetween(y1, m1, d1, y2, m2, d2) {
  return Math.round((Date.UTC(y2, m2 - 1, d2) - Date.UTC(y1, m1 - 1, d1)) / 86400000);
}

function addDays(y, m, d, n) {
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return [t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()];
}

module.exports = { validTimeZone, localParts, tzOffsetMs, localMidnightUtc, parseYmd, daysBetween, addDays };
