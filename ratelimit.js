'use strict';

// Tiny in-memory per-IP failure limiter: after `max` failures within
// `windowMs`, `blocked(ip)` is true until the window passes. IPv6 addresses
// are grouped by /64 (one home or VPS usually gets a whole /64).
function keyFor(ip) {
  const raw = String(ip || '').replace(/^::ffff:/, '');
  if (!raw.includes(':')) return raw;
  const [head] = raw.split('%');
  const parts = head.split('::');
  const left = parts[0] ? parts[0].split(':') : [];
  const right = parts.length > 1 && parts[1] ? parts[1].split(':') : [];
  const full = parts.length > 1 ? [...left, ...Array(8 - left.length - right.length).fill('0'), ...right] : left;
  return full.slice(0, 4).map((h) => h.toLowerCase().replace(/^0+(?=.)/, '')).join(':') + '::/64';
}

module.exports = Object.assign(function createLimiter({ max, windowMs }) {
  const failures = new Map();

  function entry(rawIp) {
    const ip = keyFor(rawIp);
    let e = failures.get(ip);
    if (!e || Date.now() - e.since > windowMs) {
      e = { count: 0, since: Date.now() };
      failures.set(ip, e);
    }
    return e;
  }

  return {
    blocked(rawIp) {
      const e = failures.get(keyFor(rawIp));
      return Boolean(e && e.count >= max && Date.now() - e.since < windowMs);
    },
    fail(ip) {
      entry(ip).count += 1;
      // Drop only expired entries; clearing everything would let a flood of
      // addresses wipe existing blocks.
      if (failures.size > 10_000) {
        const now = Date.now();
        for (const [k, e] of failures) if (now - e.since > windowMs) failures.delete(k);
      }
    },
  };
}, { keyFor });
