'use strict';

// Tiny in-memory per-IP failure limiter: after `max` failures within
// `windowMs`, `blocked(ip)` is true until the window passes.
module.exports = function createLimiter({ max, windowMs }) {
  const failures = new Map();

  function entry(ip) {
    let e = failures.get(ip);
    if (!e || Date.now() - e.since > windowMs) {
      e = { count: 0, since: Date.now() };
      failures.set(ip, e);
    }
    return e;
  }

  return {
    blocked(ip) {
      const e = failures.get(ip);
      return Boolean(e && e.count >= max && Date.now() - e.since < windowMs);
    },
    fail(ip) {
      entry(ip).count += 1;
      if (failures.size > 10_000) failures.clear();
    },
  };
};
