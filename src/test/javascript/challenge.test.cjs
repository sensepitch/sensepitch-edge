const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const source = readFileSync(resolve(__dirname, '../../main/resources/challenge/files/script.js'), 'utf8');
const hash = text => createHash('sha256').update(text).digest('hex');
const EXAMPLE_CHALLENGE = 'm849b8541';
const TOKEN_COOKIE = 'sensepitch-tk';
// Nonces per chunk of the PoW loop, `YIELD_EVERY` in script.js
const YIELD_EVERY = 10000;
// Bounds the fake timers `drain` runs for one challenge, a guard against a script that never stops.
const MAX_DRAIN_STEPS = 30000;

// The first nonce whose digest starts with `prefix`, computed with Node's SHA-256.
function firstNonce(challenge, prefix) {
    for (let nonce = 0; ; nonce++) if (hash(challenge + nonce).startsWith(prefix)) return nonce;
}

// Solved in the second chunk of the PoW loop, so time can pass during the calculation.
const SECOND_CHUNK_NONCE = YIELD_EVERY + 1;

// A prefix of the digest of `challenge + nonce` that no smaller nonce matches.
function prefixSolvedAt(challenge, nonce) {
    const prefix = hash(challenge + nonce).slice(0, 6);
    assert.equal(firstNonce(challenge, prefix), nonce, 'fixture: no smaller nonce matches');
    return prefix;
}

// Execute the shipped IIFE without exporting its internals or needing a browser dependency.
// Timers and clocks are controlled so failures cannot leave a PoW loop running in the background.
function browser(options = {}) {
    const timers = [];
    const requests = [];
    const images = [];
    const status = { textContent: '', firstChild: { textContent: '' } };
    let click;
    let gate = false;
    let reloads = 0;
    let scheduledTimeouts = 0;
    let postedMessages = 0;
    let wall = 1700000000000;
    let monotonic = 100;
    const checkbox = { addEventListener(_event, handler) { click = handler; } };
    Object.defineProperty(status, 'innerHTML', { set() { gate = true; } });
    const document = {
        cookie: options.cookie || '',
        hidden: options.hidden || false,
        visibilityState: options.hidden ? 'hidden' : 'visible',
        getElementById(id) {
            if (id === 'status') return status;
            if (id === 'human-checkbox') return checkbox;
            if (id === 'human-gate' && gate) return { parentNode: { removeChild() { gate = false; } } };
            return null;
        }
    };
    if (options.visibility) {
        delete document.hidden;
        delete document.visibilityState;
        Object.assign(document, options.visibility);
    }
    class XHR {
        constructor() { this.withCredentials = false; }
        open(method, url, async) {
            if (options.xhrThrows) throw new Error('XHR unavailable');
            Object.assign(this, { method, url, async });
        }
        send(body) { this.body = body; requests.push(this); }
    }
    const context = {
        document,
        CHALLENGE: options.challenge ?? EXAMPLE_CHALLENGE,
        TARGET_PREFIX: options.prefix ?? '888',
        TOKEN_COOKIE,
        MAX_ITERATIONS: options.maxIterations ?? 2000,
        ENDPOINT: '/.sensepitch.challenge.complete',
        STEP: '/.sensepitch.challenge.step',
        XMLHttpRequest: XHR,
        Image: class { set src(url) { images.push(url); } },
        navigator: { cookieEnabled: options.cookieEnabled ?? true },
        location: { reload() { reloads++; } },
        Date: class extends Date { constructor() { super(wall); } },
        performance: options.noPerformance ? undefined : { now() { return monotonic; } },
        setTimeout(callback, delay) {
            scheduledTimeouts++;
            timers.push({ callback, delay });
        }
    };
    if (options.messageChannel) {
        // Delivers each message through the fake timer queue, like a task of the browser.
        context.MessageChannel = class {
            constructor() {
                const port1 = {};
                this.port1 = port1;
                this.port2 = { postMessage() {
                    postedMessages++;
                    timers.push({ callback() { port1.onmessage({}); } });
                } };
            }
        };
    }
    context.self = context;
    vm.runInNewContext(source, context, { filename: 'script.js' });
    return {
        requests, images, status, checkbox, document,
        get reloads() { return reloads; },
        get scheduledTimeouts() { return scheduledTimeouts; },
        get postedMessages() { return postedMessages; },
        click() { assert.ok(click); click(); },
        advance(ms, wallMs = ms) { monotonic += ms; wall += wallMs; },
        // Answers the completion request; a 200 stores the admission cookie unless the browser
        // blocks cookies (`storesCookie: false`).
        respond(code) {
            const request = requests.at(-1);
            if (code === 200 && options.storesCookie !== false) {
                document.cookie = 'other=1; ' + TOKEN_COOKIE + '=token';
            }
            request.status = code;
            request.readyState = 4;
            request.onreadystatechange();
        },
        // Runs the challenge until it submits or fails, one fake timer (a PoW chunk) per step, each
        // advancing the clocks by `timerMs` (the wall clock by `timerWallMs` if given); with
        // no timer left the challenge is stuck and fails at once.
        drain() {
            for (let steps = 0; steps < MAX_DRAIN_STEPS; steps++) {
                if (requests.some(request => request.url.includes('challenge=')) ||
                    status.textContent.startsWith('Verification failed.')) return;
                const timer = timers.shift();
                if (!timer) break;
                monotonic += options.timerMs || 0;
                wall += options.timerWallMs ?? options.timerMs ?? 0;
                timer.callback();
            }
            assert.fail('challenge did not terminate');
        }
    };
}

function completion(page) {
    const request = page.requests.find(request => request.url.includes('challenge='));
    assert.ok(request, 'completion request was sent');
    assert.equal(request.method, 'GET');
    assert.equal(request.async, true);
    assert.equal(request.withCredentials, true);
    assert.equal(request.body, null);
    return new URL(request.url, 'https://example.com').searchParams;
}

function problems(page) {
    return page.requests
        .filter(request => request.url.startsWith('/.sensepitch.challenge.step'))
        .map(request => new URL(request.url, 'https://example.com').searchParams.get('event'));
}

test("solves the server's example challenge and reloads only after acceptance", () => {
    const page = browser();
    page.drain();
    const params = completion(page);
    assert.equal(params.get('challenge'), EXAMPLE_CHALLENGE);
    assert.equal(params.get('nonce'), '1658');
    assert.equal(params.get('method'), 'js');
    assert.equal(page.reloads, 0);
    page.respond(200);
    assert.equal(page.reloads, 1);
    assert.equal(page.status.textContent, 'Verified! Passing on…');
    assert.deepEqual(problems(page), []);
});

// SHA-256 padding boundaries and multiple blocks; the full digest as prefix checks every word.
for (const challenge of ['', 'a'.repeat(54), 'a'.repeat(55), 'a'.repeat(63), 'a'.repeat(64), 'a'.repeat(119), 'a'.repeat(128)]) {
    test(`SHA-256 vector of ${challenge.length} characters`, () => {
        const page = browser({ challenge, prefix: hash(challenge + '0'), maxIterations: 1 });
        page.drain();
        const params = completion(page);
        assert.equal(params.get('nonce'), '0');
        assert.equal(params.get('challenge'), challenge);
    });
}

// Prefix lengths around the digest word boundaries, each solved at the nonce Node finds first.
for (const length of [1, 7, 8, 9]) {
    test(`prefix of ${length} characters is compared exactly`, () => {
        const prefix = hash(EXAMPLE_CHALLENGE + '5').slice(0, length);
        const page = browser({ prefix, maxIterations: 1000000 });
        page.drain();
        assert.equal(completion(page).get('nonce'), String(firstNonce(EXAMPLE_CHALLENGE, prefix)));
    });
}

for (const prefix of ['impossible', '888A']) {
    test(`iteration limit stops without submitting a failed proof (prefix ${prefix})`, () => {
        const page = browser({ prefix, maxIterations: 3 });
        page.drain();
        assert.equal(page.requests.some(request => request.url.includes('challenge=')), false);
        assert.equal(page.reloads, 0);
        assert.equal(page.status.textContent, 'Verification failed. Please refresh.');
        assert.deepEqual(problems(page), ['max-iterations']);
    });
}

test('yields with a message where MessageChannel exists, never with a clamped timer', () => {
    const nonce = SECOND_CHUNK_NONCE;
    const page = browser({ prefix: prefixSolvedAt(EXAMPLE_CHALLENGE, nonce), maxIterations: nonce + 1, messageChannel: true });
    page.drain();
    assert.equal(completion(page).get('nonce'), String(nonce));
    assert.equal(page.postedMessages, 1);
    assert.equal(page.scheduledTimeouts, 0);
});

test('hashes a chunk without timers, yields between chunks and submits the first matching nonce', () => {
    const nonce = SECOND_CHUNK_NONCE;
    const page = browser({ prefix: prefixSolvedAt(EXAMPLE_CHALLENGE, nonce), maxIterations: nonce + 1, timerMs: 1 });
    page.drain();
    assert.equal(completion(page).get('nonce'), String(nonce));
    assert.equal(page.scheduledTimeouts, 1, 'one cooperative yield after the first chunk, setTimeout without MessageChannel');
    assert.equal(completion(page).get('durationMs'), '1', 'includes the yield');
});

for (const solveAt of [2, 3]) {
    test(`iteration limit 3 ${solveAt === 2 ? 'includes nonce 2' : 'excludes nonce 3'}`, () => {
        const page = browser({ prefix: prefixSolvedAt(EXAMPLE_CHALLENGE, solveAt), maxIterations: 3 });
        page.drain();
        if (solveAt === 2) assert.equal(completion(page).get('nonce'), '2');
        else assert.deepEqual(problems(page), ['max-iterations']);
    });
}

test('disabled cookies show a message without calculating', () => {
    const page = browser({ cookieEnabled: false });
    assert.equal(page.scheduledTimeouts, 0);
    assert.equal(page.requests.some(request => request.url.includes('challenge=')), false);
    assert.match(page.status.textContent, /enable cookies/);
    assert.deepEqual(problems(page), ['no-cookie']);
});

test('accepted answer without a stored cookie does not reload', () => {
    const page = browser({ prefix: '', storesCookie: false });
    page.drain();
    page.respond(200);
    assert.equal(page.reloads, 0);
    assert.match(page.status.textContent, /enable cookies/);
    assert.deepEqual(problems(page), ['no-cookie']);
});

test('a cookie whose name ends with the token cookie name does not count', () => {
    const page = browser({ prefix: '', storesCookie: false });
    page.drain();
    page.document.cookie = 'x' + TOKEN_COOKIE + '=token';
    page.respond(200);
    assert.equal(page.reloads, 0);
});

test('hidden tab reports its state and starts only once after the checkbox click', () => {
    const page = browser({ hidden: true, prefix: '', maxIterations: 1 });
    assert.equal(page.requests.length, 1);
    const initial = new URL(page.requests[0].url, 'https://example.com').searchParams;
    assert.equal(initial.get('event'), 'init-hidden');
    assert.equal(initial.get('hidden'), '1');
    assert.equal(initial.get('vis'), 'hidden');
    page.click();
    page.click();
    assert.equal(page.checkbox.disabled, true);
    page.drain();
    assert.equal(page.requests.length, 2);
    completion(page);
});

test('hidden state uses an image ping if XHR throws', () => {
    const page = browser({ hidden: true, xhrThrows: true });
    assert.equal(page.requests.length, 0);
    assert.equal(page.images.length, 1);
    assert.match(page.images[0], /event=init-hidden/);
});

for (const visibility of [{ webkitHidden: true, webkitVisibilityState: 'prerender' }, { msHidden: true }]) {
    test(`vendor visibility fallback: ${JSON.stringify(visibility)}`, () => {
        const page = browser({ visibility });
        const params = new URL(page.requests[0].url, 'https://example.com').searchParams;
        assert.equal(params.get('hidden'), '1');
        assert.equal(params.get('vis'), visibility.webkitVisibilityState || 'hidden');
    });
}

for (const code of [0, 400, 500]) {
    test(`HTTP ${code} does not reload`, () => {
        const page = browser({ prefix: '' });
        page.drain();
        page.respond(code);
        assert.equal(page.reloads, 0);
        assert.match(page.status.textContent, /^Verification failed\./);
        assert.deepEqual(problems(page), ['answer-rejected']);
    });
}

test('completion transport exception reports failure', () => {
    const page = browser({ prefix: '', xhrThrows: true });
    page.drain();
    assert.equal(page.reloads, 0);
    assert.match(page.status.textContent, /^Verification failed\./);
    assert.equal(page.images.length, 1, 'the problem report falls back to an image ping');
    assert.match(page.images[0], /event=answer-rejected/);
});

// Options for a calculation that spans one yield, during which the clocks advance.
function spanningYield(timerMs, timerWallMs) {
    return { prefix: prefixSolvedAt(EXAMPLE_CHALLENGE, SECOND_CHUNK_NONCE), maxIterations: SECOND_CHUNK_NONCE + 1, timerMs, timerWallMs };
}

test('reports completion wall time and monotonic calculation duration', () => {
    // A backwards wall-clock adjustment must not change a monotonic duration.
    const page = browser(spanningYield(12.5, -1000));
    page.drain();
    const params = completion(page);
    assert.equal(params.get('completedAt'), '1699999999000');
    assert.equal(params.get('durationMs'), '12.5');
    page.advance(500);
    page.respond(200);
    assert.equal(params.get('durationMs'), '12.5', 'network time is excluded');
});

test('duration excludes the hidden-tab wait before calculation starts', () => {
    const page = browser({ hidden: true, ...spanningYield(25) });
    page.advance(10000);
    page.click();
    page.drain();
    const params = completion(page);
    assert.equal(params.get('completedAt'), '1700000010025');
    assert.equal(params.get('durationMs'), '25');
});

for (const elapsed of [35, -35]) {
    test(`wall-clock fallback reports a nonnegative duration (${elapsed} ms)`, () => {
        const page = browser({ noPerformance: true, ...spanningYield(elapsed) });
        page.drain();
        const params = completion(page);
        assert.equal(params.get('completedAt'), String(1700000000000 + elapsed));
        assert.equal(params.get('durationMs'), String(Math.max(0, elapsed)));
    });
}
