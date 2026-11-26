(function () {
    var status = document.getElementById('status');

    // --- CONFIG ---
    var YIELD_EVERY = 10000; // iterations per chunk
    // Reported in the answer, the server records it as telemetry only.
    var METHOD = 'js';
    var COOKIE_MESSAGE = 'Please enable cookies for this site and refresh.';
    var maxIterations = (typeof MAX_ITERATIONS === 'number' && MAX_ITERATIONS > 0)
        ? MAX_ITERATIONS
        : 0;

    // --- UTIL: visibility with vendor fallbacks ---
    function docHidden() {
        if (typeof document.hidden !== 'undefined') return document.hidden;
        if (typeof document.webkitHidden !== 'undefined') return document.webkitHidden;
        if (typeof document.msHidden !== 'undefined') return document.msHidden;
        // If unsupported, assume visible
        return false;
    }

    function visState() {
        return document.visibilityState ||
            document.webkitVisibilityState ||
            (docHidden() ? "hidden" : "visible");
    }

    // --- Transport cascade ---
    function sendOnce(baseUrl, params, withCreds) {
        var qs = [];
        for (var key in params) if (params.hasOwnProperty(key)) {
            qs.push(encodeURIComponent(key) + "=" + encodeURIComponent(params[key]));
        }
        var url = baseUrl + (baseUrl.indexOf("?") === -1 ? "?" : "&") + qs.join("&");

        // The unadmitted challenge endpoints accept GET only. This reports the initial
        // hidden-tab state, so XHR is sufficient; sendBeacon would always send POST.
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("GET", url, true);
            if (withCreds === true) xhr.withCredentials = true;
            xhr.send(null);
            return;
        } catch (e) {}

        // Fallback: image ping
        try {
            var img = new Image();
            img.src = url;
            setTimeout(function () { img = null; }, 5000);
        } catch (e2) {}
    }

    function onVisibilityChange(handler) {
        if (typeof document.addEventListener !== 'function') return;
        var ev = 'visibilitychange';
        if (typeof document.hidden === 'undefined') {
            if (typeof document.webkitHidden !== 'undefined') ev = 'webkitvisibilitychange';
            else if (typeof document.msHidden !== 'undefined') ev = 'msvisibilitychange';
        }
        document.addEventListener(ev, handler, true);
    }

    // --- SHA-256 ---
    // One synchronous pure JavaScript implementation for every browser. It hashes about 27 times
    // as fast as one WebCrypto digest per nonce and allocates nothing per hash; the measurement
    // and the reasoning are in doc/design/pow-admission.md.
    var K = new Int32Array([
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    ]);
    var W = new Int32Array(64);
    var H = new Int32Array(8);

    // Hashes an ASCII string into H. The input is the server's base-23 challenge plus decimal
    // digits, so every character is one byte and goes straight into the message words.
    function sha256(ascii) {
        var n = ascii.length;
        var blocks = ((n + 8) >> 6) + 1;
        var h0 = 0x6a09e667, h1 = 0xbb67ae85 | 0, h2 = 0x3c6ef372, h3 = 0xa54ff53a | 0;
        var h4 = 0x510e527f, h5 = 0x9b05688c | 0, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
        var a, b, c, d, e, f, g, h, i, j, x, y, t1, t2;
        for (var block = 0; block < blocks; block++) {
            var base = block << 6;
            var length = Math.min(n - base, 64);
            for (j = 0; j < 16; j++) W[j] = 0;
            for (i = 0; i < length; i++) W[i >> 2] |= ascii.charCodeAt(base + i) << (24 - ((i & 3) << 3));
            if (length >= 0 && length < 64) W[length >> 2] |= 0x80 << (24 - ((length & 3) << 3));
            if (block === blocks - 1) W[15] = n << 3;
            for (j = 16; j < 64; j++) {
                x = W[j - 15];
                y = W[j - 2];
                W[j] = (W[j - 16] + ((x >>> 7 | x << 25) ^ (x >>> 18 | x << 14) ^ (x >>> 3)) + W[j - 7]
                    + ((y >>> 17 | y << 15) ^ (y >>> 19 | y << 13) ^ (y >>> 10))) | 0;
            }
            a = h0; b = h1; c = h2; d = h3; e = h4; f = h5; g = h6; h = h7;
            for (j = 0; j < 64; j++) {
                t1 = (h + ((e >>> 6 | e << 26) ^ (e >>> 11 | e << 21) ^ (e >>> 25 | e << 7))
                    + ((e & f) ^ (~e & g)) + K[j] + W[j]) | 0;
                t2 = (((a >>> 2 | a << 30) ^ (a >>> 13 | a << 19) ^ (a >>> 22 | a << 10))
                    + ((a & b) ^ (a & c) ^ (b & c))) | 0;
                h = g; g = f; f = e; e = (d + t1) | 0;
                d = c; c = b; b = a; a = (t1 + t2) | 0;
            }
            h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
            h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
        }
        H[0] = h0; H[1] = h1; H[2] = h2; H[3] = h3; H[4] = h4; H[5] = h5; H[6] = h6; H[7] = h7;
    }

    // Returns a test whether the digest of a string starts with the hex `prefix`. The prefix is
    // parsed once into digest words and masks, a test then needs no hex string, which would cost
    // about a third of the hashing time. A prefix with other characters than lowercase hex digits
    // never matches, as on the server.
    function prefixMatcher(prefix) {
        if (!/^[0-9a-f]{0,64}$/.test(prefix)) return function () { return false; };
        var words = [], masks = [];
        for (var i = 0; i < prefix.length; i += 8) {
            var part = prefix.substr(i, 8);
            var bits = part.length * 4;
            words.push(parseInt(part, 16) << (32 - bits));
            masks.push(bits === 32 ? -1 : ((1 << bits) - 1) << (32 - bits));
        }
        return function (ascii) {
            sha256(ascii);
            for (var w = 0; w < words.length; w++) {
                if (((H[w] ^ words[w]) & masks[w]) !== 0) return false;
            }
            return true;
        };
    }

    // Continues with `callback` after the browser handled its other events. A message is not
    // subject to the minimum delay of 4 ms that browsers impose on nested timers, which would cost
    // more than the hashing of a chunk on a fast machine; setTimeout only in browsers without
    // MessageChannel (before 2013).
    var yieldToBrowser = (function () {
        if (typeof MessageChannel !== 'function') {
            return function (callback) { setTimeout(callback, 0); };
        }
        var channel = new MessageChannel();
        var next;
        channel.port1.onmessage = function () { next(); };
        return function (callback) {
            next = callback;
            channel.port2.postMessage(0);
        };
    })();

    // --- PoW loop (chunked; cooperative) ---
    // Hashes synchronously and yields to the browser after every YIELD_EVERY attempts.
    function runPoW(done) {
        status.textContent = 'Testing browser…';
        var nonce = 0;
        var solves = prefixMatcher(TARGET_PREFIX);
        function chunk() {
            var end = nonce + YIELD_EVERY;
            for (; nonce < end; nonce++) {
                if (maxIterations > 0 && nonce >= maxIterations) {
                    return done(new Error('max iterations exceeded'));
                }
                if (solves(CHALLENGE + nonce)) {
                    return done(null, nonce);
                }
            }
            status.textContent = status.textContent + '.';
            yieldToBrowser(chunk);
        }
        chunk();
    }


    // --- Network: XHR with credentials ---
    function httpGetWithCreds(url, cb) {
        try {
            var xhr = new XMLHttpRequest();
            if ('withCredentials' in xhr) {
                xhr.open('GET', url, true);
                xhr.withCredentials = true;
                xhr.onreadystatechange = function () {
                    if (xhr.readyState === 4) {
                        // status 0 can occur for opaque/cors issues; treat as failure
                        var ok = (xhr.status >= 200 && xhr.status < 300);
                        cb(null, ok);
                    }
                };
                xhr.onerror = function () { cb(null, false); };
                xhr.send(null);
            } else {
                // XDomainRequest (old IE) could go here, but cookies + CORS are tricky.
                cb(null, false);
            }
        } catch (e) {
            cb(null, false);
        }
    }

    // --- UI gate for hidden tabs ---
    function renderGate() {
        if (document.getElementById('human-gate')) return;
        status.innerHTML = ''
            + '<div>To continue, please verify you are human.</div>'
            + '<label id="human-gate" class="gate">'
            + '  <input id="human-checkbox" type="checkbox" />'
            + '  <span>Please click to verify that you are a human</span>'
            + '</label>'
            + '<div class="muted">A browser verification will start after you click the checkbox</div>';

        var cb = document.getElementById('human-checkbox');
        var clicked = false;
        cb.addEventListener('click', function () {
            if (clicked) return;
            clicked = true;
            cb.disabled = true;
            cb.checked = true;
            status.firstChild.textContent = 'Thanks! Starting verification…';
            startFlow();
        }, { once: true });
    }

    // Browser traits for the answer, to spot a client that impersonates a browser by its
    // User-Agent, never to tell users apart; see doc/design/browser-fingerprinting.md.
    // Returns query parameters with a leading '&'; a value the browser lacks is left out,
    // since its absence is a trait as well.
    function browserTraits() {
        var qs = '&tzOffset=' + encodeURIComponent(new Date().getTimezoneOffset());
        try {
            var tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
            if (typeof tz === 'string' && tz) qs += '&tz=' + encodeURIComponent(tz);
        } catch (_e) {}
        var webdriver = self.navigator && self.navigator.webdriver;
        if (typeof webdriver === 'boolean') qs += '&webdriver=' + (webdriver ? 1 : 0);
        return qs;
    }

    var started = false;
    function startFlow() {
        if (started) return;
        started = true;
        var gate = document.getElementById('human-gate');
        // removeChild, since Element.remove() is missing in old browsers
        if (gate) gate.parentNode.removeChild(gate);

        // Measure only the PoW run, including the cooperative yield time.
        // Prefer a monotonic clock so wall-clock corrections cannot distort the duration.
        var now = (self.performance && typeof self.performance.now === 'function')
            ? function () { return self.performance.now(); }
            : function () { return new Date().getTime(); };
        var calculationStarted = now();
        runPoW(function (err, nonce) {
            if (err) {
                status.textContent = 'Verification failed. Please refresh.';
                reportProblem('max-iterations');
                return;
            }
            var durationMs = Math.max(0, now() - calculationStarted);
            var completedAt = new Date().getTime();
            status.textContent = 'Requesting access…';
            var qs = 'challenge=' + encodeURIComponent(CHALLENGE)
                + '&nonce=' + encodeURIComponent(nonce)
                + '&completedAt=' + encodeURIComponent(completedAt)
                + '&durationMs=' + encodeURIComponent(durationMs)
                + '&method=' + encodeURIComponent(METHOD)
                + browserTraits();
            httpGetWithCreds(ENDPOINT + '?' + qs, function (_err, ok) {
                if (!ok) {
                    status.textContent = 'Verification failed. Please refresh or return to the tab and try again.';
                    reportProblem('answer-rejected');
                } else if (!hasCookie(TOKEN_COOKIE)) {
                    // a reload without the admission cookie would show the challenge again, endlessly
                    status.textContent = COOKIE_MESSAGE;
                    reportProblem('no-cookie');
                } else {
                    status.textContent = 'Verified! Passing on…';
                    try { location.reload(); } catch (_e2) { location.href = location.href; }
                }
            });
        });
    }

    // Whether the browser stored the named cookie, readable since the server does not set it
    // HttpOnly.
    function hasCookie(name) {
        return ('; ' + (document.cookie || '')).indexOf('; ' + name + '=') !== -1;
    }

    // Tells the server why a visitor got stuck; `event` is one of a fixed set of values, recorded
    // in the request log.
    function reportProblem(event) {
        sendOnce(STEP, { event: event }, true);
    }

    // --- Boot ---
    // Without cookies the admission cannot be stored, so the calculation would be in vain.
    if (self.navigator && self.navigator.cookieEnabled === false) {
        status.textContent = COOKIE_MESSAGE;
        reportProblem('no-cookie');
        return;
    }

    // If initially hidden, show gate; else start
    if (docHidden()) {
        sendOnce(STEP, { hidden: docHidden() ? 1 : 0, vis: visState(), event: "init-hidden" }, true);
        renderGate();
    } else {
        status.textContent = 'Checking your browser…';
        startFlow();
    }

// If it becomes visible before clicking, start automatically (remove this if you want click to be mandatory)
//    onVisibilityChange(function () {
//        if (!docHidden() && !started) {
//            status.textContent = 'Checking your browser…';
//            startFlow();
//        }
//    });

})();
