// Fallbacks for older Android WebViews (e-readers like Supernote, Boox and Kobo often ship
// Chrome < 117). Each is only added when the browser lacks it. Loaded before foliate-js.
(() => {
    const def = (obj, name, fn) => { if (!(name in obj)) Object.defineProperty(obj, name, { value: fn, writable: true, configurable: true }) }
    def(Object, 'groupBy', (items, key) => {
        const out = Object.create(null); let i = 0
        for (const item of items) { const k = key(item, i++); (out[k] ??= []).push(item) }
        return out
    })
    def(Map, 'groupBy', (items, key) => {
        const out = new Map(); let i = 0
        for (const item of items) { const k = key(item, i++); if (!out.has(k)) out.set(k, []); out.get(k).push(item) }
        return out
    })
    const at = function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; return n < 0 || n >= this.length ? undefined : this[n] }
    def(Array.prototype, 'at', at); def(String.prototype, 'at', at)
    def(Array.prototype, 'findLast', function (f, t) { for (let i = this.length - 1; i >= 0; i--) if (f.call(t, this[i], i, this)) return this[i] })
    def(Array.prototype, 'findLastIndex', function (f, t) { for (let i = this.length - 1; i >= 0; i--) if (f.call(t, this[i], i, this)) return i; return -1 })
    def(Array.prototype, 'toSorted', function (c) { return [...this].sort(c) })
    def(Array.prototype, 'toReversed', function () { return [...this].reverse() })
    def(Promise, 'withResolvers', () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } })
    def(Object, 'hasOwn', (o, k) => Object.prototype.hasOwnProperty.call(o, k))
    if (typeof globalThis.structuredClone !== 'function') {
        // Good enough for the plain data foliate clones (no Blobs, Maps or cycles).
        globalThis.structuredClone = v => v === undefined ? undefined : JSON.parse(JSON.stringify(v))
    }
})()
