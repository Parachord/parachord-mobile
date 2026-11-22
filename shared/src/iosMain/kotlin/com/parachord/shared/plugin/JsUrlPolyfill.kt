package com.parachord.shared.plugin

/**
 * `URL` and `URLSearchParams` for the iOS plug-in host. A bare
 * JavaScriptCore `JSContext` has neither (they're WebCore APIs, like
 * `DOMParser`), while desktop's Electron renderer and Android's WebView have
 * both, so plug-ins call `new URL(...)` freely. Without them every `new URL`
 * threw a ReferenceError, which a plug-in's own `try` read as "not a URL":
 * `amazonmusic.axe`'s `isAmazonMusicPage` answered false for the album page
 * it had just loaded, so every Amazon album lookup on iOS failed as "not
 * reachable", and its sign-in gate (`onMusicHost`) could never pass.
 *
 * Each is defined only where missing. It covers what plug-ins use: absolute
 * URLs and relative ones against a base, the WHATWG special schemes (http,
 * https, ws, wss, ftp; default ports dropped, host lowercased, dot segments
 * resolved, backslashes as slashes, percent-encoding per component), opaque
 * URLs (`about:blank`, `mailto:`, `data:`) with origin "null", the component
 * getters and setters, and a `searchParams` that writes back to the URL. Not
 * covered: IDNA (a non-ASCII host is only lowercased) and IPv4/IPv6 host
 * normalization. Tested in JavaScriptCore by `JsUrlPolyfillTest`.
 */
internal object JsUrlPolyfill {
    val SCRIPT: String = """
(function () {
    var g = (typeof globalThis !== 'undefined') ? globalThis : this;

    function formEncode(s) {
        return encodeURIComponent(s).replace(/%20/g, '+').replace(/[!'()~]/g, function (c) {
            return '%' + c.charCodeAt(0).toString(16).toUpperCase();
        });
    }
    function formDecode(s) {
        s = s.replace(/\+/g, ' ');
        try { return decodeURIComponent(s); } catch (e) { return s; }
    }

    if (typeof g.URLSearchParams === 'undefined') {
        var USP = function URLSearchParams(init) {
            this._list = [];
            this._url = null;
            if (init == null) return;
            if (init instanceof USP) {
                this._list = init._list.map(function (p) { return [p[0], p[1]]; });
            } else if (Array.isArray(init)) {
                for (var i = 0; i < init.length; i++) {
                    var pair = init[i];
                    if (!pair || pair.length !== 2) throw new TypeError('URLSearchParams: each pair needs exactly two items');
                    this._list.push([String(pair[0]), String(pair[1])]);
                }
            } else if (typeof init === 'object') {
                var self = this;
                Object.keys(init).forEach(function (k) { self._list.push([k, String(init[k])]); });
            } else {
                this._parse(String(init));
            }
        };
        USP.prototype._parse = function (s) {
            this._list = [];
            if (s.charAt(0) === '?') s = s.slice(1);
            var parts = s.split('&');
            for (var i = 0; i < parts.length; i++) {
                if (!parts[i]) continue;
                var eq = parts[i].indexOf('=');
                var name = eq < 0 ? parts[i] : parts[i].slice(0, eq);
                var value = eq < 0 ? '' : parts[i].slice(eq + 1);
                this._list.push([formDecode(name), formDecode(value)]);
            }
        };
        USP.prototype._changed = function () {
            if (this._url) this._url._setQueryFromParams(this.toString());
        };
        USP.prototype.append = function (n, v) { this._list.push([String(n), String(v)]); this._changed(); };
        USP.prototype['delete'] = function (n) {
            n = String(n);
            this._list = this._list.filter(function (p) { return p[0] !== n; });
            this._changed();
        };
        USP.prototype.get = function (n) {
            n = String(n);
            for (var i = 0; i < this._list.length; i++) if (this._list[i][0] === n) return this._list[i][1];
            return null;
        };
        USP.prototype.getAll = function (n) {
            n = String(n);
            return this._list.filter(function (p) { return p[0] === n; }).map(function (p) { return p[1]; });
        };
        USP.prototype.has = function (n) { return this.get(n) !== null; };
        USP.prototype.set = function (n, v) {
            n = String(n); v = String(v);
            var out = [], found = false;
            for (var i = 0; i < this._list.length; i++) {
                if (this._list[i][0] !== n) { out.push(this._list[i]); continue; }
                if (!found) { out.push([n, v]); found = true; }
            }
            if (!found) out.push([n, v]);
            this._list = out;
            this._changed();
        };
        USP.prototype.sort = function () {
            this._list = this._list.map(function (p, i) { return [p, i]; })
                .sort(function (a, b) { return a[0][0] < b[0][0] ? -1 : a[0][0] > b[0][0] ? 1 : a[1] - b[1]; })
                .map(function (x) { return x[0]; });
            this._changed();
        };
        USP.prototype.forEach = function (fn, thisArg) {
            var copy = this._list.slice();
            for (var i = 0; i < copy.length; i++) fn.call(thisArg, copy[i][1], copy[i][0], this);
        };
        USP.prototype.entries = function () { return this._list.map(function (p) { return [p[0], p[1]]; })[Symbol.iterator](); };
        USP.prototype.keys = function () { return this._list.map(function (p) { return p[0]; })[Symbol.iterator](); };
        USP.prototype.values = function () { return this._list.map(function (p) { return p[1]; })[Symbol.iterator](); };
        USP.prototype[Symbol.iterator] = USP.prototype.entries;
        Object.defineProperty(USP.prototype, 'size', { get: function () { return this._list.length; } });
        USP.prototype.toString = function () {
            return this._list.map(function (p) { return formEncode(p[0]) + '=' + formEncode(p[1]); }).join('&');
        };
        g.URLSearchParams = USP;
    }

    if (typeof g.URL !== 'undefined') return;

    var SPECIAL = { 'http:': '80', 'https:': '443', 'ws:': '80', 'wss:': '443', 'ftp:': '21', 'file:': '' };

    // Characters WHATWG percent-encodes in each component; an existing %XX is left alone.
    var PATH_RE = /[\u0000- "<>`{}^|\u007F-￿]/g;
    var QUERY_RE = /[\u0000- "<>'\u007F-￿]/g;
    var FRAG_RE = /[\u0000- "<>`\u007F-￿]/g;
    function safeEncode(s, re) {
        // encodeURIComponent needs a whole surrogate pair, so take pairs first.
        return s.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, function (p) { return encodeURIComponent(p); })
            .replace(re, function (c) {
                try { return encodeURIComponent(c); } catch (e) { return '%EF%BF%BD'; }
            });
    }

    function removeDots(path) {
        var segs = path.split('/');
        var out = [];
        for (var i = 1; i < segs.length; i++) {
            var s = segs[i];
            var low = s.toLowerCase();
            var last = i === segs.length - 1;
            if (s === '..' || low === '%2e%2e' || low === '.%2e' || low === '%2e.') {
                if (out.length) out.pop();
                if (last) out.push('');
            } else if (s === '.' || low === '%2e') {
                if (last) out.push('');
            } else {
                out.push(s);
            }
        }
        return '/' + out.join('/');
    }

    function splitTail(rest) {
        var hash = null, query = null;
        var h = rest.indexOf('#');
        if (h >= 0) { hash = rest.slice(h + 1); rest = rest.slice(0, h); }
        var q = rest.indexOf('?');
        if (q >= 0) { query = rest.slice(q + 1); rest = rest.slice(0, q); }
        return { path: rest, query: query, hash: hash };
    }

    function parseAuthority(auth, scheme) {
        var r = { username: '', password: '', host: '', port: '' };
        var at = auth.lastIndexOf('@');
        if (at >= 0) {
            var cred = auth.slice(0, at);
            auth = auth.slice(at + 1);
            var c = cred.indexOf(':');
            r.username = c >= 0 ? cred.slice(0, c) : cred;
            r.password = c >= 0 ? cred.slice(c + 1) : '';
        }
        var bracket = auth.charAt(0) === '[' ? auth.indexOf(']') : -1;
        var colon = auth.lastIndexOf(':');
        if (colon > bracket) {
            r.port = auth.slice(colon + 1);
            auth = auth.slice(0, colon);
            if (!/^\d*$/.test(r.port)) throw new TypeError('Invalid URL');
        }
        r.host = auth.toLowerCase();
        var bare = bracket >= 0 ? '' : r.host;
        if (/[\u0000- #%\/:<>?@\[\\\]^|]/.test(bare)) throw new TypeError('Invalid URL');
        if (r.port !== '') {
            var n = parseInt(r.port, 10);
            if (n > 65535) throw new TypeError('Invalid URL');
            r.port = String(n);
            if (SPECIAL[scheme] === r.port) r.port = '';
        }
        return r;
    }

    var SCHEME_RE = /^([a-zA-Z][a-zA-Z0-9+.\-]*):/;

    function parseAbsolute(input) {
        var m = SCHEME_RE.exec(input);
        if (!m) return null;
        var scheme = m[1].toLowerCase() + ':';
        var rest = input.slice(m[0].length);
        var u = { protocol: scheme, username: '', password: '', hostname: '', port: '', pathname: '',
                  query: null, fragment: null, special: SPECIAL.hasOwnProperty(scheme), opaque: false, hasAuthority: false };
        if (u.special) {
            rest = rest.replace(/\\/g, '/');
            if (scheme === 'file:') {
                rest = rest.replace(/^\/*/, '');
                var tf = splitTail('/' + rest);
                u.pathname = removeDots(tf.path);
                u.query = tf.query; u.fragment = tf.hash; u.hasAuthority = true;
                return u;
            }
            rest = rest.replace(/^\/*/, '');
            var end = rest.search(/[\/?#]/);
            var auth = end < 0 ? rest : rest.slice(0, end);
            rest = end < 0 ? '' : rest.slice(end);
            var a = parseAuthority(auth, scheme);
            if (!a.host) throw new TypeError('Invalid URL');
            u.username = a.username; u.password = a.password; u.hostname = a.host; u.port = a.port;
            var t = splitTail(rest);
            u.pathname = removeDots(t.path || '/');
            u.query = t.query; u.fragment = t.hash;
            return u;
        }
        if (rest.slice(0, 2) === '//') {
            rest = rest.slice(2);
            var e2 = rest.search(/[\/?#]/);
            var auth2 = e2 < 0 ? rest : rest.slice(0, e2);
            rest = e2 < 0 ? '' : rest.slice(e2);
            var a2 = parseAuthority(auth2, scheme);
            u.username = a2.username; u.password = a2.password; u.hostname = a2.host; u.port = a2.port;
            var t2 = splitTail(rest);
            u.pathname = t2.path ? removeDots(t2.path) : '';
            u.query = t2.query; u.fragment = t2.hash;
            u.hasAuthority = true;
            return u;
        }
        var t3 = splitTail(rest);
        u.query = t3.query; u.fragment = t3.hash;
        u.opaque = t3.path.charAt(0) !== '/';
        u.pathname = u.opaque ? t3.path : removeDots(t3.path);
        return u;
    }

    function copy(o) { var r = {}; for (var k in o) r[k] = o[k]; return r; }

    function resolve(input, base) {
        var u = copy(base);
        u.fragment = null;
        if (base.special) input = input.replace(/\\/g, '/');
        if (input.slice(0, 2) === '//') return parseAbsolute(base.protocol + input);
        if (base.opaque) {
            if (input.charAt(0) === '#') { u.fragment = input.slice(1); return u; }
            throw new TypeError('Invalid URL');
        }
        if (input === '') return u;
        if (input.charAt(0) === '#') { u.fragment = input.slice(1); return u; }
        var t = splitTail(input);
        if (input.charAt(0) === '?') { u.query = t.query; u.fragment = t.hash; return u; }
        var path;
        if (t.path.charAt(0) === '/') {
            path = t.path;
        } else {
            path = (base.pathname.slice(0, base.pathname.lastIndexOf('/') + 1) || '/') + t.path;
        }
        u.pathname = removeDots(path);
        u.query = t.query; u.fragment = t.hash;
        return u;
    }

    function parse(input, base) {
        input = String(input).replace(/^[\u0000- ]+|[\u0000- ]+$/g, '').replace(/[\t\n\r]/g, '');
        var abs = parseAbsolute(input);
        if (abs) {
            // "https:foo" against an https base is relative to it (WHATWG).
            var afterScheme = input.slice(input.indexOf(':') + 1);
            if (base && abs.special && base.protocol === abs.protocol && !/^[\/\\]/.test(afterScheme)) {
                return resolve(afterScheme, base);
            }
            return abs;
        }
        if (!base) throw new TypeError('Invalid URL: ' + input);
        return resolve(input, base);
    }

    function URL(url, base) {
        if (!(this instanceof URL)) throw new TypeError("Failed to construct 'URL': Please use the 'new' operator.");
        var b = null;
        if (base !== undefined) b = (base instanceof URL) ? base._u : parse(String(base), null);
        var u = copy(parse(String(url), b));
        if (!u.opaque) u.pathname = safeEncode(u.pathname, PATH_RE);
        if (u.query !== null) u.query = safeEncode(u.query, QUERY_RE);
        if (u.fragment !== null) u.fragment = safeEncode(u.fragment, FRAG_RE);
        this._u = u;
        this._params = null;
    }
    function hostOf(u) { return u.hostname + (u.port ? ':' + u.port : ''); }
    function hrefOf(u) {
        var s = u.protocol;
        if (u.special || u.hasAuthority) {
            s += '//';
            if (u.username || u.password) s += u.username + (u.password ? ':' + u.password : '') + '@';
            s += hostOf(u);
        }
        s += u.pathname;
        if (u.query !== null) s += '?' + u.query;
        if (u.fragment !== null) s += '#' + u.fragment;
        return s;
    }
    function def(name, get, set) {
        Object.defineProperty(URL.prototype, name, { get: get, set: set, enumerable: true, configurable: true });
    }
    def('href', function () { return hrefOf(this._u); }, function (v) {
        this._u = new URL(v)._u;
        if (this._params) this._params._parse(this._u.query || '');
    });
    def('protocol', function () { return this._u.protocol; });
    def('username', function () { return this._u.username; });
    def('password', function () { return this._u.password; });
    def('host', function () { return hostOf(this._u); });
    def('hostname', function () { return this._u.hostname; });
    def('port', function () { return this._u.port; });
    def('origin', function () {
        var u = this._u;
        if (u.protocol === 'blob:') { try { return new URL(u.pathname).origin; } catch (e) { return 'null'; } }
        if (!u.special || u.protocol === 'file:') return 'null';
        return u.protocol + '//' + hostOf(u);
    });
    def('pathname', function () { return this._u.pathname; }, function (v) {
        if (this._u.opaque) return;
        v = String(v);
        if (this._u.special) v = v.replace(/\\/g, '/');
        if (v.charAt(0) !== '/') v = '/' + v;
        this._u.pathname = safeEncode(removeDots(v), PATH_RE);
    });
    def('search', function () { return this._u.query ? '?' + this._u.query : ''; }, function (v) {
        v = String(v);
        if (v.charAt(0) === '?') v = v.slice(1);
        this._u.query = v === '' ? null : safeEncode(v, QUERY_RE);
        if (this._params) this._params._parse(this._u.query || '');
    });
    def('hash', function () { return this._u.fragment ? '#' + this._u.fragment : ''; }, function (v) {
        v = String(v);
        if (v.charAt(0) === '#') v = v.slice(1);
        this._u.fragment = v === '' ? null : safeEncode(v, FRAG_RE);
    });
    def('searchParams', function () {
        if (!this._params) {
            this._params = new g.URLSearchParams(this._u.query || '');
            this._params._url = this;
        }
        return this._params;
    });
    URL.prototype._setQueryFromParams = function (q) { this._u.query = q === '' ? null : q; };
    URL.prototype.toString = function () { return this.href; };
    URL.prototype.toJSON = function () { return this.href; };
    URL.canParse = function (url, base) { try { new URL(url, base); return true; } catch (e) { return false; } };
    URL.parse = function (url, base) { try { return new URL(url, base); } catch (e) { return null; } };
    g.URL = URL;
})();
"""
}
