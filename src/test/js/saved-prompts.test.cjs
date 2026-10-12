const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'index.html'), 'utf8');
const script = html.slice(html.indexOf('let savedPrompts = []'), html.indexOf('// \u2500\u2500\u2500 File selection'));

class Element {
  constructor() {
    this.children = [];
    this.value = '';
    this.textContent = '';
    this.disabled = false;
    this.open = false;
    this.classList = { toggle() {} };
  }
  replaceChildren() { this.children = []; }
  append(...children) { this.children.push(...children); }
  setAttribute() {}
  showModal() { this.open = true; }
  close() { this.open = false; }
  focus() { this.focused = true; }
  querySelectorAll() { return this.children.filter(child => child.onclick); }
}

function setup() {
  const elements = new Map();
  const requests = [];
  const responses = [];
  const context = vm.createContext({
    document: {
      getElementById(id) {
        if (!elements.has(id)) elements.set(id, new Element());
        return elements.get(id);
      },
      createElement() { return new Element(); }
    },
    window: { prompt: () => 'Name' },
    confirm: () => true,
    autoResize: element => element.resized = true,
    apiFetch: async (url, options) => {
      requests.push({ url, options });
      const response = responses.shift();
      if (response instanceof Error) throw response;
      return response;
    }
  });
  vm.runInContext(script, context);
  const element = id => context.document.getElementById(id);
  const respond = (data, status = 200) => responses.push({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    text: async () => JSON.stringify(data),
    json: async () => data
  });
  return { context, element, requests, responses, respond };
}

test('saving preserves exact prompt text and reports success', async () => {
  const { context, element, requests, respond } = setup();
  element('prompt').value = '  A scene\nwith trees  ';
  respond({ name: 'Name' }, 201);
  await context.saveCurrentPrompt();
  assert.deepEqual(JSON.parse(requests[0].options.body), { name: 'Name', prompt: element('prompt').value });
  assert.equal(element('saved-prompts-status').textContent, 'Saved "Name".');
  assert.equal(element('save-prompt-btn').disabled, false);
});

test('empty prompts, cancelled naming and blank names do not save', async () => {
  const { context, element, requests } = setup();
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /Enter a prompt/);
  element('prompt').value = 'text';
  context.window.prompt = () => null;
  await context.saveCurrentPrompt();
  context.window.prompt = () => ' ';
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /Name is required/);
  assert.equal(requests.length, 0);
});

test('duplicate names and network failures report errors without changing prompt', async () => {
  const { context, element, responses, respond } = setup();
  element('prompt').value = 'original';
  respond({ error: 'That name already exists. Choose another name.' }, 409);
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /already exists/);
  responses.push(new Error('Network unavailable'));
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /Network unavailable/);
  assert.equal(element('prompt').value, 'original');
  assert.equal(element('save-prompt-btn').disabled, false);
});

test('load popup renders names as text and loading changes only the prompt', async () => {
  const { context, element, respond } = setup();
  element('model').value = 'gpt-image-2';
  element('size').value = 'auto';
  respond([{ name: '<script>unsafe & "name"</script>', prompt: '  saved\ntext  ' }]);
  await context.openSavedPrompts();
  const row = element('saved-prompts-list').children[0];
  assert.equal(row.children[0].textContent, '<script>unsafe & "name"</script>');
  assert.equal(element('saved-prompts-dialog').open, true);
  row.children[1].onclick();
  assert.equal(element('prompt').value, '  saved\ntext  ');
  assert.equal(element('prompt').resized, true);
  assert.equal(element('prompt').focused, true);
  assert.equal(element('model').value, 'gpt-image-2');
  assert.equal(element('size').value, 'auto');
  assert.equal(element('saved-prompts-dialog').open, false);
});

test('delete requires confirmation and removes a row only after success', async () => {
  const { context, element, requests, respond } = setup();
  respond([{ name: 'name / &', prompt: 'text' }]);
  await context.openSavedPrompts();
  const row = element('saved-prompts-list').children[0];
  context.confirm = () => false;
  await row.children[2].onclick();
  assert.equal(requests.length, 1);
  assert.equal(element('saved-prompts-list').children.length, 1);
  context.confirm = () => true;
  respond(null, 204);
  await row.children[2].onclick();
  assert.equal(requests[1].url, '/api/saved-prompts?name=name%20%2F%20%26');
  assert.equal(requests[1].options.method, 'DELETE');
  assert.equal(element('saved-prompts-list').children.length, 0);
  assert.equal(element('saved-prompts-dialog').open, true);
  assert.match(element('saved-prompts-dialog-status').textContent, /Deleted/);
});

test('failed deletion keeps the row and re-enables its buttons', async () => {
  const { context, element, respond } = setup();
  respond([{ name: 'name', prompt: 'text' }]);
  await context.openSavedPrompts();
  const row = element('saved-prompts-list').children[0];
  respond({ error: 'Storage unavailable' }, 503);
  await row.children[2].onclick();
  assert.equal(element('saved-prompts-list').children[0], row);
  assert.equal(row.children[1].disabled, false);
  assert.equal(row.children[2].disabled, false);
  assert.match(element('saved-prompts-dialog-status').textContent, /Storage unavailable/);
});

test('empty lists and load failures are explicit and clear stale rows', async () => {
  const { context, element, respond } = setup();
  respond([]);
  await context.openSavedPrompts();
  assert.match(element('saved-prompts-dialog-status').textContent, /No saved prompts/);
  element('saved-prompts-dialog').close();
  respond({ error: 'Storage unavailable' }, 503);
  await context.openSavedPrompts();
  assert.equal(element('saved-prompts-list').children.length, 0);
  assert.match(element('saved-prompts-dialog-status').textContent, /Storage unavailable/);
  assert.equal(element('load-prompt-btn').disabled, false);
});
