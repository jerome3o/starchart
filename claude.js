'use strict';

// Claude API features: the in-app chat (Claude with every Starchart MCP tool)
// and the writer/classifier behind nudges. Enabled when ANTHROPIC_API_KEY is set.

const AnthropicModule = require('@anthropic-ai/sdk');
const Anthropic = AnthropicModule.default || AnthropicModule;
const { zodOutputFormat } = require('@anthropic-ai/sdk/helpers/zod');
const { mcpTools } = require('@anthropic-ai/sdk/helpers/beta/mcp');
const { betaZodOutputFormat } = require('@anthropic-ai/sdk/helpers/beta/zod');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { InMemoryTransport } = require('@modelcontextprotocol/sdk/inMemory.js');
const z = require('zod/v4');
const { buildServer, simplifyListedSchemas } = require('./mcp');
const { SCOPES } = require('./oauth');
const time = require('./time');
const db = require('./db');

const CHAT_MODEL = 'claude-opus-5';
const WRITER_MODEL = 'claude-opus-5';
const CLASSIFIER_MODEL = 'claude-haiku-4-5';
// On a policy decline the API re-runs the request on a fallback model.
const FALLBACK = { betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' };

let client = null;
function enabled() {
  return Boolean(process.env.ANTHROPIC_API_KEY);
}
function anthropic() {
  if (!client) client = new Anthropic();
  return client;
}

function nowLine(email) {
  const tz = db.getTimezone(email);
  const p = time.localParts(Date.now(), tz);
  const pad = (n) => String(n).padStart(2, '0');
  return `${p.weekday} ${p.year}-${pad(p.month)}-${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)} (${tz})`;
}

// --- Chat --------------------------------------------------------------------

const CHAT_SYSTEM = `You are Claude, living inside Starchart: the user's personal habit-goal "star chart" app on their Android phone, with location tracking, a home-screen widget and an e-ink dashboard.

You have the same tools as Starchart's MCP server: goals (list, log, undo, create, update — including each goal's description of what it is and why the user does it), nudge history and settings, location history and gap diagnostics, and phone commands (diagnostics, restart tracking, sync, fresh fix, widget refresh, notifications). Use them freely to answer; call get_overview when you need the current picture.

The user likes banter: be warm and direct with a dry, teasing edge — happy to roast them a little when they're slacking, genuinely pleased when they're winning. Never cruel. Replies are read on a phone: keep them short, plain text, no markdown headings or tables. When you change something (log a completion, edit a goal, send a command), say what you did.

Each user message starts with the current local time in square brackets.`;

const MAX_HISTORY = 40;

/**
 * One chat turn. `history` is [{role: 'user'|'assistant', content: string}],
 * ending with the new user message. Returns {reply, tools}.
 */
async function chat(email, deviceLabel, history) {
  const messages = history.slice(-MAX_HISTORY).map((m, i, arr) => ({
    role: m.role,
    content: i === arr.length - 1 ? `[${nowLine(email)}]\n${m.content}` : m.content,
  }));
  while (messages.length && messages[0].role !== 'user') messages.shift();
  if (!messages.length || messages[messages.length - 1].role !== 'user') {
    throw new Error('history must end with a user message');
  }

  // The chat gets every MCP tool (the user is signed in on their own phone),
  // run in-process through the same server the external MCP clients use.
  const server = buildServer(email, Object.keys(SCOPES), `Starchart chat (${deviceLabel})`);
  simplifyListedSchemas(server);
  const [serverSide, clientSide] = InMemoryTransport.createLinkedPair();
  const mcpClient = new Client({ name: 'starchart-chat', version: '1.0.0' });
  await server.connect(serverSide);
  await mcpClient.connect(clientSide);
  try {
    const { tools } = await mcpClient.listTools();
    const runner = anthropic().beta.messages.toolRunner({
      model: CHAT_MODEL,
      max_tokens: 16000,
      thinking: { type: 'adaptive' },
      output_config: { effort: 'medium' },
      ...FALLBACK,
      cache_control: { type: 'ephemeral' },
      system: CHAT_SYSTEM,
      tools: mcpTools(tools, mcpClient),
      messages,
      max_iterations: 15,
    });
    const used = [];
    for await (const message of runner) {
      for (const block of message.content) if (block.type === 'tool_use') used.push(block.name);
    }
    const final = await runner.done();
    let reply = final.content.filter((b) => b.type === 'text').map((b) => b.text).join('\n').trim();
    if (final.stop_reason === 'refusal') reply = reply || "I can't help with that one.";
    if (!reply) reply = used.length ? 'Done.' : '…';
    return { reply, tools: used };
  } finally {
    await mcpClient.close().catch(() => {});
    await server.close().catch(() => {});
  }
}

// --- Nudges ------------------------------------------------------------------

const Decision = z.object({
  send: z.boolean(),
  reason: z.string(),
});

/** Cheap first pass: is now a good moment to nudge about this goal? */
async function shouldNudge(context) {
  const response = await anthropic().messages.parse({
    model: CLASSIFIER_MODEL,
    max_tokens: 400,
    system: `You decide whether to send the user a reminder notification about one habit goal right now. They are behind pace on it. Send only when a reminder now is likely to lead to action: a sensible moment for this kind of goal (e.g. morning before the gym, evening for chores, not while they're probably commuting or asleep), considering when they usually do it, how far behind they are, how many days are left, and how recently they were already nudged. Don't nag: if they were nudged about this goal in the last few hours or already did it today, usually say no. Reply with send and a one-sentence reason.`,
    messages: [{ role: 'user', content: JSON.stringify(context, null, 2) }],
    output_config: { format: zodOutputFormat(Decision) },
  });
  return response.parsed_output || { send: false, reason: 'no decision' };
}

const Notification = z.object({
  title: z.string().describe('Notification title, at most 40 characters'),
  text: z.string().describe('Notification body, at most 160 characters'),
});

/** Writes the actual notification in the roast voice. */
async function writeNudge(context) {
  const response = await anthropic().beta.messages.parse({
    model: WRITER_MODEL,
    max_tokens: 2000,
    thinking: { type: 'adaptive' },
    output_config: { effort: 'low', format: betaZodOutputFormat(Notification) },
    ...FALLBACK,
    system: `You write one phone notification nudging the user to do a habit goal they're behind on. The user asked to be roasted: be sassy, cheeky and funny — a mate taking the piss, not a wellness app. Use their own reason for the goal against them when you have it, reference the actual numbers (done vs expected, days left) and the time of day. Vary it: no catchphrase openers, no generic motivational-poster lines. Short: title at most 40 characters, body at most 160. At most one emoji. Never cruel about their body, health or worth — the target is the procrastination.`,
    messages: [{ role: 'user', content: JSON.stringify(context, null, 2) }],
  });
  const out = response.parsed_output;
  if (!out) throw new Error(`writer returned no notification (stop_reason ${response.stop_reason})`);
  return { title: out.title.slice(0, 60), text: out.text.slice(0, 240) };
}

module.exports = { enabled, chat, shouldNudge, writeNudge, nowLine };
