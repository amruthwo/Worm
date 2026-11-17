// Worm Metamorphosis (GPL-3.0-or-later). Turns anything foliate-js can open (MOBI, AZW3/KF8, FB2,
// FBZ, CBZ) — plus plain HTML — into EPUB parts: XHTML sections, resources, contents, metadata.
import { makeBook } from '../foliate/view.js'

const A = window.Worm
const SRC = 'https://appassets.androidplatform.net/src/'

const EXT = {
    'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/svg+xml': 'svg',
    'image/webp': 'webp', 'image/bmp': 'bmp', 'text/css': 'css', 'font/ttf': 'ttf', 'font/otf': 'otf',
    'font/woff': 'woff', 'font/woff2': 'woff2', 'application/vnd.ms-opentype': 'otf', 'application/x-font-ttf': 'ttf',
    'application/font-sfnt': 'ttf',
}
const sniff = async blob => {
    const b = new Uint8Array(await blob.slice(0, 12).arrayBuffer())
    if (b[0] === 0xFF && b[1] === 0xD8) return 'image/jpeg'
    if (b[0] === 0x89 && b[1] === 0x50) return 'image/png'
    if (b[0] === 0x47 && b[1] === 0x49) return 'image/gif'
    if (b[8] === 0x57 && b[9] === 0x45) return 'image/webp'
    return blob.type || 'application/octet-stream'
}

const toBase64 = async blob => {
    const bytes = new Uint8Array(await blob.arrayBuffer())
    let s = ''
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000))
    return btoa(s)
}

const text = x => !x ? '' : typeof x === 'string' ? x : x.name ? text(x.name) : (Object.values(x)[0] ?? '')
const people = x => (Array.isArray(x) ? x : x ? [x] : []).map(text).filter(Boolean)

let resN = 0
const resMap = new Map()
async function resource(url) {
    if (resMap.has(url)) return resMap.get(url)
    const res = await fetch(url)
    const blob = await res.blob()
    let type = blob.type && blob.type !== 'application/octet-stream' ? blob.type.split(';')[0] : await sniff(blob)
    const href = `res/r${resN++}.${EXT[type] ?? 'bin'}`
    resMap.set(url, href)
    if (type === 'text/css') A.resourceText(href, type, await rewriteCss(await blob.text(), url))
    else A.resource(href, type, await toBase64(blob), false)
    return href
}

// url(...) inside CSS: blob: or relative (HTML mode) → files next to the stylesheet in res/.
async function rewriteCss(css, base) {
    const out = []
    let last = 0
    for (const m of css.matchAll(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g)) {
        out.push(css.slice(last, m.index))
        const raw = m[2]
        let replaced = m[0]
        if (!/^(data:|#)/.test(raw)) {
            try {
                const abs = new URL(raw, base).href
                const href = await resource(abs)
                replaced = `url("${href.replace('res/', '')}")`
            } catch (_) {}
        }
        out.push(replaced)
        last = m.index + m[0].length
    }
    out.push(css.slice(last))
    return out.join('')
}

const isExternal = href => /^(https?|mailto|tel|ftp):/i.test(href)

async function localize(doc, base) {
    for (const s of doc.querySelectorAll('script')) s.remove()
    const attrs = [['img', 'src'], ['image', 'href'], ['image', 'xlink:href'], ['link', 'href'], ['source', 'src'], ['video', 'poster'], ['audio', 'src'], ['object', 'data'], ['use', 'xlink:href'], ['use', 'href']]
    for (const [tag, attr] of attrs) {
        for (const el of doc.getElementsByTagName(tag)) {
            const v = el.getAttribute(attr)
            if (!v || /^(data:|#)/.test(v) || isExternal(v)) continue
            if (tag === 'link' && !/stylesheet/i.test(el.getAttribute('rel') ?? '')) continue
            try { el.setAttribute(attr, '../' + await resource(new URL(v, base).href)) }
            catch (_) { if (tag === 'img') el.remove() }
        }
    }
    for (const el of doc.querySelectorAll('[style]')) {
        const s = el.getAttribute('style')
        if (/url\(/.test(s)) el.setAttribute('style', (await rewriteCss(s, base)).replaceAll('url("r', 'url("../res/r'))
    }
    for (const st of doc.getElementsByTagName('style')) {
        if (/url\(/.test(st.textContent)) st.textContent = (await rewriteCss(st.textContent, base)).replaceAll('url("r', 'url("../res/r')
    }
}

const name = i => `s${String(i).padStart(4, '0')}.xhtml`

function serialize(doc, title) {
    const html = doc.documentElement
    if (!html.getAttribute('xmlns') && doc.contentType === 'text/html') html.setAttribute('xmlns', 'http://www.w3.org/1999/xhtml')
    let head = doc.head ?? html.querySelector('head')
    if (!head) { head = doc.createElement('head'); html.prepend(head) }
    if (!head.querySelector('title')) { const t = doc.createElement('title'); t.textContent = title; head.prepend(t) }
    const xml = new XMLSerializer().serializeToString(html)
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + xml
}

async function fixedLayoutPage(doc) {
    // Comic pages: one image filling the page, with a viewport matching the image.
    const img = doc.querySelector('img')
    if (!img) return
    const src = img.getAttribute('src')
    const size = await new Promise(res => {
        const probe = new Image()
        probe.onload = () => res([probe.naturalWidth, probe.naturalHeight])
        probe.onerror = () => res([1200, 1800])
        probe.src = src
    })
    const meta = doc.createElement('meta')
    meta.setAttribute('name', 'viewport'); meta.setAttribute('content', `width=${size[0]}, height=${size[1]}`)
    doc.head.append(meta)
    doc.body.setAttribute('style', 'margin:0;padding:0')
    img.setAttribute('style', 'width:100%;height:100%;object-fit:contain')
}

async function convertHtml(fileName) {
    const url = SRC + encodeURIComponent(fileName)
    const raw = await (await fetch(url)).text()
    const doc = new DOMParser().parseFromString(raw, 'text/html')
    const title = doc.title || fileName.replace(/\.[^.]+$/, '')
    await localize(doc, url)
    // Contents from headings.
    const toc = []
    let n = 0
    for (const h of doc.querySelectorAll('h1, h2, h3')) {
        if (!h.id) h.id = `worm-h${n++}`
        toc.push({ label: h.textContent.trim(), href: 'text/' + name(0) + '#' + h.id, depth: Number(h.tagName[1]) - 1 })
    }
    A.section(serialize(doc, title))
    A.done(JSON.stringify({ title, authors: [], language: doc.documentElement.lang || 'en', toc, fixed: false }))
}

async function convertBook(fileName) {
    const res = await fetch(SRC + encodeURIComponent(fileName))
    const file = new File([await res.blob()], fileName)
    const book = await makeBook(file)
    const meta = book.metadata ?? {}
    const rawTitle = text(meta.title)
    const title = (rawTitle && rawTitle !== fileName) ? rawTitle : fileName.replace(/\.[^.]+$/, '')
    const fixed = book.rendition?.layout === 'pre-paginated'

    // 1. Load every section (resources come back as blob: URLs).
    const docs = []
    for (const [i, s] of book.sections.entries()) {
        A.progress(0.6 * i / book.sections.length)
        if (!s.load || s.linear === 'no' && !s.size) { docs.push(null); continue }
        try {
            const url = await s.load()
            const r = await fetch(url)
            const type = r.headers.get('content-type') ?? ''
            const src = await r.text()
            let doc = new DOMParser().parseFromString(src, /xhtml|xml/.test(type) ? 'application/xhtml+xml' : 'text/html')
            if (doc.querySelector('parsererror')) doc = new DOMParser().parseFromString(src, 'text/html')
            docs.push({ doc, url })
        } catch (e) { A.log('section ' + i + ' failed: ' + e); docs.push(null) }
    }
    const outIndex = new Map()
    docs.forEach((d, i) => { if (d) outIndex.set(i, outIndex.size) })

    // 2. Contents + internal links: resolve to section + element id.
    let idN = 0
    const ensureId = el => { if (!el.id) el.id = `worm-${idN++}`; return el.id }
    async function target(href) {
        let r = null
        try { r = await book.resolveHref(href) } catch (_) {}
        if (!r || r.index == null || !outIndex.has(r.index)) return null
        let el = null
        try { el = r.anchor?.(docs[r.index].doc) } catch (_) {}
        if (el && el.nodeType !== 1) el = el.startContainer?.parentElement ?? el.parentElement ?? null
        return name(outIndex.get(r.index)) + (el && el.nodeType === 1 ? '#' + ensureId(el) : '')
    }
    const toc = []
    const walk = async (items, depth) => {
        for (const it of items ?? []) {
            const t = it.href ? await target(it.href) : null
            if (t) toc.push({ label: (it.label ?? '').trim(), href: 'text/' + t, depth })
            if (it.subitems?.length) await walk(it.subitems, t ? depth + 1 : depth)
        }
    }
    await walk(book.toc, 0)
    for (const d of docs) {
        if (!d) continue
        for (const a of d.doc.querySelectorAll('a[href]')) {
            const href = a.getAttribute('href')
            if (isExternal(href) || href.startsWith('blob:')) continue
            const t = await target(href)
            if (t) a.setAttribute('href', t); else a.removeAttribute('href')
        }
        for (const el of d.doc.querySelectorAll('[filepos]')) el.removeAttribute('filepos')
    }

    // 3. Cover, then sections with their resources, in reading order.
    try {
        const cover = await book.getCover?.()
        if (cover) { const type = await sniff(cover); A.resource('res/cover.' + (EXT[type] ?? 'jpg'), type, await toBase64(cover), true) }
    } catch (_) {}
    let k = 0
    for (const d of docs) {
        if (!d) continue
        await localize(d.doc, d.url)
        if (fixed) await fixedLayoutPage(d.doc)
        A.section(serialize(d.doc, title))
        A.progress(0.6 + 0.4 * (++k) / outIndex.size)
    }

    A.done(JSON.stringify({
        title,
        authors: people(meta.author),
        language: (Array.isArray(meta.language) ? meta.language[0] : meta.language) || 'en',
        description: text(meta.description).replace(/<[^>]+>/g, ' ').trim() || null,
        publisher: text(meta.publisher) || null,
        toc,
        fixed,
    }))
    book.destroy?.()
}

window.metamorphosis = async fileName => {
    try {
        if (/\.(x?html?)$/i.test(fileName)) await convertHtml(fileName)
        else await convertBook(fileName)
    } catch (e) {
        A.fail(String(e?.message ?? e))
    }
}
A.onBridge()
