#!/usr/bin/env node
// Chat with a local Ollama model that can use the DuckInsurance MCP tools. No dependencies, Node 20 or newer, no internet needed once the model is pulled.
//
//   node scripts/duckchat.mjs                      interactive
//   node scripts/duckchat.mjs "Which ducks does Anna Keller have?"
//
// Settings (environment): OLLAMA_URL (http://localhost:11434), OLLAMA_MODEL (qwen3.8:27b), MCP_URL (http://localhost:8081/api/mcp), MCP_USER / MCP_PASSWORD (customer / customer)
// The role of MCP_USER decides what the model may do: 'customer' cannot decide claims, 'adjuster' and 'admin' can.
import readline from 'node:readline/promises';

const OLLAMA = process.env.OLLAMA_URL ?? 'http://localhost:11434';
const MODEL = process.env.OLLAMA_MODEL ?? 'qwen3.8:27b';
const MCP = process.env.MCP_URL ?? 'http://localhost:8081/api/mcp';
const USER = process.env.MCP_USER ?? 'customer';
const PASS = process.env.MCP_PASSWORD ?? USER;
const VERSION = '2026-07-28';
const AUTH = 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64');

let nextId = 1;

async function rpc(method, params = {}, name) {
    const id = nextId++;
    const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': VERSION, 'Mcp-Method': method, 'Authorization': AUTH };
    if (name) headers['Mcp-Name'] = name;
    const body = { jsonrpc: '2.0', id, method, params: { ...params, _meta: { 'io.modelcontextprotocol/protocolVersion': VERSION, 'io.modelcontextprotocol/clientCapabilities': {} } } };
    const res = await fetch(MCP, { method: 'POST', headers, body: JSON.stringify(body) });
    const text = await res.text();
    if (!res.ok) throw new Error(`MCP ${method}: HTTP ${res.status} ${text.slice(0, 200)}`);
    let msg;
    if ((res.headers.get('content-type') ?? '').includes('text/event-stream')) {
        for (const block of text.split(/\r?\n\r?\n/)) {
            const data = block.split(/\r?\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
            if (data) { try { const m = JSON.parse(data); if (m.id === id) msg = m; } catch { /* ignore */ } }
        }
    } else {
        msg = JSON.parse(text);
    }
    if (!msg) throw new Error(`MCP ${method}: no answer`);
    if (msg.error) throw new Error(`MCP ${method}: ${msg.error.message}`);
    return msg.result;
}

async function chat(messages, tools) {
    const res = await fetch(`${OLLAMA}/api/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: MODEL, messages, tools, stream: false }) });
    if (!res.ok) throw new Error(`Ollama: HTTP ${res.status} ${(await res.text()).slice(0, 300)}`);
    return (await res.json()).message;
}

const listed = await rpc('tools/list');
const tools = listed.tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description ?? '', parameters: t.inputSchema ?? { type: 'object', properties: {} } } }));
console.log(`Connected to ${MCP} as ${USER}: ${tools.length} tools. Model: ${MODEL}\n`);

const SYSTEM = 'You are the assistant of DuckInsurance, an insurer for ducks. Use the tools to answer. Look up ids with the list and search tools instead of guessing them. Only change data when the user asks for it. Answer briefly.';

async function ask(history, question) {
    history.push({ role: 'user', content: question });
    for (let round = 0; round < 8; round++) {
        const message = await chat(history, tools);
        history.push(message);
        if (!message.tool_calls?.length) return message.content;
        for (const call of message.tool_calls) {
            const { name, arguments: args } = call.function;
            console.log(`  → ${name}(${JSON.stringify(args)})`);
            let text;
            try {
                const result = await rpc('tools/call', { name, arguments: args ?? {} }, name);
                text = (result.content ?? []).map(c => c.text ?? '').join('\n') || JSON.stringify(result);
                if (result.isError) text = 'ERROR: ' + text;
            } catch (e) {
                text = 'ERROR: ' + e.message;
            }
            console.log(`  ← ${text.length > 160 ? text.slice(0, 160) + '…' : text}`);
            history.push({ role: 'tool', tool_name: name, content: text });
        }
    }
    return '(stopped after 8 tool rounds)';
}

const history = [{ role: 'system', content: SYSTEM }];
const once = process.argv.slice(2).join(' ').trim();
if (once) {
    console.log(await ask(history, once));
} else {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    for (;;) {
        const line = (await rl.question('you> ')).trim();
        if (!line || line === 'exit') break;
        console.log('\n' + await ask(history, line) + '\n');
    }
    rl.close();
}
