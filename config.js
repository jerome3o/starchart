'use strict';

// The allow-list, shared by every way in: Google login, OAuth grants, personal
// API tokens and device tokens. Re-checked on every request, so removing an
// email from ALLOWED_EMAILS cuts off all of that account's access at once.
const allowedEmails = (process.env.ALLOWED_EMAILS || '')
  .split(',')
  .map((s) => s.trim().toLowerCase())
  .filter(Boolean);

function isAllowed(email) {
  return Boolean(email) && allowedEmails.includes(String(email).toLowerCase());
}

module.exports = { allowedEmails, isAllowed };
