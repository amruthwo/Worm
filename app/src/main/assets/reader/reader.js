// Worm reader bridge (GPL-3.0-or-later). Uses foliate-js for parsing/rendering.
import '../foliate/view.js'
import { Overlayer } from '../foliate/overlayer.js'

const A = window.Worm
const send = (name, obj) => A[name](JSON.stringify(obj ?? {}))
let view = null
let settings = {}
let lastRange = null, lastIndex = 0
const annotations = new Map() // cfi -> { value, color, note }

const flattenToc = (items, depth = 0, out = []) => {
    for (const it of items ?? []) {
        out.push({ label: (it.label ?? '').trim(), href: it.href ?? '', depth })
        if (it.subitems?.length) flattenToc(it.subitems, depth + 1, out)
    }
    return out
}

const FONT_BASE = 'https://appassets.androidplatform.net/assets/fonts/'
const fontFaces = `
    @font-face { font-family: 'Worm Literata'; src: url('${FONT_BASE}Literata.ttf'); font-weight: 200 900; }
    @font-face { font-family: 'Worm Literata'; src: url('${FONT_BASE}Literata-Italic.ttf'); font-weight: 200 900; font-style: italic; }
    @font-face { font-family: 'Worm Lora'; src: url('${FONT_BASE}Lora.ttf'); font-weight: 400 700; }
    @font-face { font-family: 'Worm Lora'; src: url('${FONT_BASE}Lora-Italic.ttf'); font-weight: 400 700; font-style: italic; }
    @font-face { font-family: 'Worm Garamond'; src: url('${FONT_BASE}EBGaramond.ttf'); font-weight: 400 800; }
    @font-face { font-family: 'Worm Garamond'; src: url('${FONT_BASE}EBGaramond-Italic.ttf'); font-weight: 400 800; font-style: italic; }
    @font-face { font-family: 'Worm Atkinson'; src: url('${FONT_BASE}AtkinsonHyperlegible-Regular.ttf'); }
    @font-face { font-family: 'Worm Atkinson'; src: url('${FONT_BASE}AtkinsonHyperlegible-Bold.ttf'); font-weight: bold; }
    @font-face { font-family: 'Worm Atkinson'; src: url('${FONT_BASE}AtkinsonHyperlegible-Italic.ttf'); font-style: italic; }
`

const themeCss = s => `
    @namespace epub "http://www.idpf.org/2007/ops";
    ${fontFaces}
    html {
        color-scheme: ${s.dark ? 'dark' : 'light'};
        font-size: ${s.fontSize}px !important;
        -webkit-text-size-adjust: none;
    }
    html, body { background: transparent !important; color: ${s.fg} !important; }
    body { font-size: 1rem; }
    ${s.fontFamily !== 'publisher' ? `body, p, div, span, li, blockquote, a, em, i, b, strong, h1, h2, h3, h4, h5, h6 { font-family: ${s.fontStack} !important; }` : ''}
    p, li, blockquote, dd, div {
        line-height: ${s.lineHeight} !important;
        text-align: ${s.justify ? 'justify' : 'start'};
        -webkit-hyphens: ${s.hyphenate ? 'auto' : 'manual'};
        hyphens: ${s.hyphenate ? 'auto' : 'manual'};
        widows: 2; orphans: 2;
    }
    [align="left"] { text-align: left; } [align="right"] { text-align: right; } [align="center"] { text-align: center; }
    pre { white-space: pre-wrap !important; }
    a:link, a:visited { color: ${s.link} !important; }
    ${s.forceColors ? `body * { color: inherit !important; background-color: transparent !important; border-color: ${s.fg}33 !important; }` : ''}
    ${s.dimImages ? 'img, svg, image { filter: brightness(.82) contrast(1.05); }' : ''}
    ::selection { background: ${s.selection}; }
    aside[epub|type~="footnote"], aside[epub|type~="endnote"], aside[epub|type~="note"], aside[epub|type~="rearnote"] { display: none; }
`

function applySettings(s) {
    settings = s
    document.documentElement.style.setProperty('--bg', s.bg)
    document.documentElement.style.setProperty('--fg', s.fg)
    // The book's frame and this page must agree on light/dark, or the WebView paints the frame
    // with its own grey canvas instead of letting our theme colour show through.
    document.documentElement.style.colorScheme = s.dark ? 'dark' : 'light'
    if (!view?.renderer) return
    const r = view.renderer
    r.setAttribute('flow', s.scrolled ? 'scrolled' : 'paginated')
    r.setAttribute('margin', `${s.margin}px`)
    r.setAttribute('gap', `${Math.max(4, Math.round(s.margin / 4))}%`)
    r.setAttribute('max-inline-size', '720px')
    r.setAttribute('max-column-count', s.columns ?? 1)
    if (s.instant) r.removeAttribute('animated'); else r.setAttribute('animated', '')
    r.setStyles?.(themeCss(s))
}

// ------------------------------------------------------------------ XPointers
// KOReader (and KOSync) locate positions with crengine XPointers:
//   /body/DocFragment[N]/body/div[2]/p[5]/text().12
// N = spine index + 1; element steps count same-name siblings (1-based, index left off when the
// name is unique); the text step gives a character offset (text()[K].N when there are several).

function toXPointer(index, range) {
    if (!range) return null
    let node = range.startContainer, offset = range.startOffset
    let textStep = ''
    if (node.nodeType === Node.ELEMENT_NODE) {
        // Point at the child element the range starts before, if any.
        const child = node.childNodes[offset]
        if (child?.nodeType === Node.ELEMENT_NODE) { node = child; offset = 0 }
        else if (child?.nodeType === Node.TEXT_NODE) { node = child; offset = 0 }
    }
    if (node.nodeType === Node.TEXT_NODE) {
        const parent = node.parentElement
        const texts = [...parent.childNodes].filter(n => n.nodeType === Node.TEXT_NODE)
        const k = texts.indexOf(node)
        textStep = texts.length > 1 ? `/text()[${k + 1}].${offset}` : `/text().${offset}`
        node = parent
    }
    const doc = node.ownerDocument
    const body = doc.body ?? doc.querySelector('body')
    const steps = []
    for (let el = node; el && el !== body && el !== doc.documentElement; el = el.parentElement) {
        const name = el.localName
        const same = [...el.parentElement.children].filter(c => c.localName === name)
        steps.unshift(same.length > 1 ? `${name}[${same.indexOf(el) + 1}]` : name)
    }
    return `/body/DocFragment[${index + 1}]/body` + (steps.length ? '/' + steps.join('/') : '') + textStep
}

async function fromXPointer(xp) {
    const m = xp.match(/^\/body\/DocFragment(?:\[(\d+)\])?\/body(.*)$/)
    if (!m) return null
    const index = m[1] ? Number(m[1]) - 1 : 0
    const section = view.book.sections[index]
    if (!section?.createDocument) return null
    const doc = await section.createDocument()
    let el = doc.body ?? doc.querySelector('body')
    let range = doc.createRange()
    range.selectNodeContents(el); range.collapse(true)
    for (const step of m[2].split('/').filter(Boolean)) {
        const t = step.match(/^text\(\)(?:\[(\d+)\])?\.(\d+)$/)
        if (t) {
            const texts = [...el.childNodes].filter(n => n.nodeType === Node.TEXT_NODE)
            const node = texts[(t[1] ? Number(t[1]) : 1) - 1]
            if (node) { range = doc.createRange(); range.setStart(node, Math.min(Number(t[2]), node.length)); range.collapse(true) }
            break
        }
        const e = step.match(/^([\w:-]+)(?:\[(\d+)\])?$/)
        if (!e) break
        const next = [...el.children].filter(c => c.localName.toLowerCase() === e[1].toLowerCase())[(e[2] ? Number(e[2]) : 1) - 1]
        if (!next) break // keep the deepest element we could find
        el = next
        range = doc.createRange(); range.selectNodeContents(el); range.collapse(true)
    }
    return view.getCFI(index, range)
}

/** How far through the whole book a CFI is (0–1): its section's start + how far into that section's text. */
async function fractionAtCFI(cfi) {
    const { index, anchor } = view.resolveCFI(cfi)
    const sf = view.getSectionFractions()
    const start = sf[index] ?? 0, end = sf[index + 1] ?? 1
    const doc = await view.book.sections[index].createDocument()
    const body = doc.body ?? doc.documentElement
    const total = body.textContent.length || 1
    let target = null
    try { target = anchor(doc) } catch (_) {}
    if (!target) return start
    const r = doc.createRange()
    r.setStart(body, 0)
    if (target.startContainer) r.setEnd(target.startContainer, target.startOffset)
    else r.setEndBefore(target)
    return start + (end - start) * Math.min(1, r.toString().length / total)
}

function tapZone(e, doc) {
    if (doc.getSelection()?.toString()) return
    if (e.target.closest?.('a[href]')) return
    const frame = doc.defaultView.frameElement
    const rect = frame ? frame.getBoundingClientRect() : { left: 0, top: 0 }
    const x = (rect.left + e.clientX) / window.innerWidth
    const y = (rect.top + e.clientY) / window.innerHeight
    const zone = settings.scrolled ? (y < 0.25 ? 'prev' : y > 0.75 ? 'next' : 'menu')
        : (x < 0.28 ? 'left' : x > 0.72 ? 'right' : 'menu')
    if (zone === 'left') view.goLeft()
    else if (zone === 'right') view.goRight()
    else if (zone === 'prev') view.prev()
    else if (zone === 'next') view.next()
    A.onTap(zone)
}

// Pinch to change text size; drag up/down along the right edge to change brightness.
let suppressClick = false
function gestures(doc) {
    let pinch0 = 0, edge = null
    const frameLeft = () => doc.defaultView.frameElement?.getBoundingClientRect().left ?? 0
    const dist = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY)
    doc.addEventListener('touchstart', e => {
        if (e.touches.length === 2) { pinch0 = dist(e.touches); edge = null; e.stopImmediatePropagation(); return }
        const t = e.touches[0]
        const x = (frameLeft() + t.clientX) / window.innerWidth
        edge = x > 0.88 ? { y: t.clientY, active: false } : null
    }, { capture: true, passive: false })
    doc.addEventListener('touchmove', e => {
        if (pinch0 && e.touches.length === 2) {
            e.preventDefault(); e.stopImmediatePropagation()
            const ratio = dist(e.touches) / pinch0
            if (ratio > 1.18 || ratio < 0.85) { A.onPinch(ratio > 1 ? 1 : -1); pinch0 = dist(e.touches) }
            return
        }
        if (edge) {
            const dy = e.touches[0].clientY - edge.y
            if (!edge.active && Math.abs(dy) > 14) edge.active = true
            if (edge.active) {
                e.preventDefault(); e.stopImmediatePropagation()
                A.onBrightness(-dy / window.innerHeight)
                edge.y = e.touches[0].clientY
            }
        }
    }, { capture: true, passive: false })
    doc.addEventListener('touchend', e => {
        if (pinch0 || edge?.active) { suppressClick = true; setTimeout(() => suppressClick = false, 350); e.stopImmediatePropagation() }
        if (e.touches.length < 2) pinch0 = 0
        if (edge?.active) A.onBrightnessEnd()
        edge = null
    }, { capture: true })
}

function onLoad({ doc, index }) {
    gestures(doc)
    doc.addEventListener('click', e => { if (!suppressClick) tapZone(e, doc) })
    let t
    doc.addEventListener('selectionchange', () => {
        clearTimeout(t)
        t = setTimeout(() => {
            const has = !!doc.getSelection()?.toString()?.trim()
            if (has) try { currentSelection() } catch (_) {}
            A.onSelection(has ? 1 : 0)
        }, 120)
    })
    doc.addEventListener('keydown', e => {
        if (e.key === 'ArrowLeft') view.goLeft()
        else if (e.key === 'ArrowRight') view.goRight()
    })
}

// The last real selection, kept for a few seconds: tapping a menu action can collapse the
// selection before Kotlin asks for it, which used to make Define silently do nothing.
let lastSel = null
function currentSelection() {
    for (const { doc, index } of view.renderer.getContents()) {
        const sel = doc.getSelection()
        if (sel && sel.rangeCount && !sel.isCollapsed) {
            const range = sel.getRangeAt(0)
            let cfi = ''
            try { cfi = view.getCFI(index, range) } catch (e) { A.log('selection cfi: ' + e) }
            lastSel = { text: sel.toString().trim(), cfi, sel, at: Date.now() }
            return lastSel
        }
    }
    return lastSel && Date.now() - lastSel.at < 8000 ? lastSel : null
}

window.worm = {
    async open(cfg) {
        try {
            applySettings(cfg.settings)
            const res = await fetch('/book/' + encodeURIComponent(cfg.fileName))
            const blob = await res.blob()
            const file = new File([blob], cfg.fileName, { type: blob.type })
            view = document.createElement('foliate-view')
            document.body.prepend(view)
            await view.open(file)
            view.addEventListener('load', e => onLoad(e.detail))
            view.addEventListener('relocate', e => {
                const d = e.detail
                lastRange = d.range ?? null
                try { lastIndex = view.resolveCFI(d.cfi).index } catch (_) {}
                let xpointer = null
                try { xpointer = toXPointer(view.resolveCFI(d.cfi).index, d.range) } catch (_) {}
                // Debug builds: prove the XPointer round-trips back to (nearly) the same CFI.
                if (window.wormDebug && xpointer) fromXPointer(xpointer).then(back =>
                    A.log(`xp ${xpointer} -> ${back === d.cfi ? 'SAME' : 'DIFF'} (${d.cfi} | ${back})`)).catch(e => A.log('xp fail ' + e))
                send('onRelocate', {
                    cfi: d.cfi, fraction: d.fraction ?? 0, xpointer,
                    chapter: d.tocItem?.label?.trim() ?? '', chapterHref: d.tocItem?.href ?? '',
                    page: d.location?.current ?? 0, pages: d.location?.total ?? 0,
                    sectionPage: d.section?.current ?? 0, sectionPages: d.section?.total ?? 0,
                    pageLabel: d.pageItem?.label ?? '',
                })
            })
            view.addEventListener('create-overlay', e => {
                for (const a of annotations.values()) {
                    try { if (view.resolveCFI(a.value).index === e.detail.index) view.addAnnotation(a) } catch (_) {}
                }
            })
            view.addEventListener('draw-annotation', e => {
                const { draw, annotation } = e.detail
                if (annotation.color === 'underline') draw(Overlayer.underline, { color: settings.link, width: 2 })
                else draw(Overlayer.highlight, { color: annotation.color })
            })
            view.addEventListener('show-annotation', e => A.onAnnotationTap(e.detail.value))
            view.addEventListener('external-link', e => { e.preventDefault(); A.openLink(e.detail.a.href) })
            for (const h of cfg.highlights ?? []) annotations.set(h.cfi, { value: h.cfi, color: h.color, note: h.note })
            applySettings(cfg.settings)
            const book = view.book
            const title = typeof book.metadata?.title === 'string' ? book.metadata.title : Object.values(book.metadata?.title ?? {})[0]
            send('onReady', {
                title: title ?? '', toc: flattenToc(book.toc), fractions: view.getSectionFractions(), dir: book.dir ?? 'ltr',
                fixed: !!view.isFixedLayout,
            })
            await view.init({ lastLocation: cfg.cfi || null, showTextStart: !cfg.cfi && !(cfg.fraction > 0) && !cfg.xpointer })
            // Converted books keep your percentage (the old file's exact spot doesn't exist in the new one).
            if (!cfg.cfi && cfg.xpointer) await window.worm.goToXPointer(cfg.xpointer, cfg.fraction || null)
            else if (!cfg.cfi && cfg.fraction > 0) await view.goToFraction(cfg.fraction)
            document.getElementById('veil').classList.add('gone')
        } catch (err) {
            A.onError(String(err?.message ?? err))
        }
    },
    settings: s => applySettings(s),
    next: () => view?.next(),
    prev: () => view?.prev(),
    left: () => view?.goLeft(),
    right: () => view?.goRight(),
    goTo: target => view?.goTo(target).catch(e => A.log(String(e))),
    goToFraction: f => view?.goToFraction(f),
    /** Jump to a KOReader XPointer; falls back to the percentage if it can't be resolved. */
    async goToXPointer(xp, fraction) {
        try {
            const cfi = await fromXPointer(xp)
            if (cfi) { await view.goTo(cfi); return }
        } catch (e) { A.log('xpointer: ' + e) }
        if (fraction != null) await view.goToFraction(fraction)
    },
    back: () => view?.history.back(),
    selection() {
        const s = currentSelection()
        return JSON.stringify(s ? { text: s.text, cfi: s.cfi } : null)
    },
    clearSelection() {
        lastSel = null
        for (const { doc } of view.renderer.getContents()) doc.getSelection()?.removeAllRanges()
    },
    addHighlight(cfi, color, note) {
        const a = { value: cfi, color, note }
        if (annotations.has(cfi)) view.deleteAnnotation(annotations.get(cfi))
        annotations.set(cfi, a)
        view.addAnnotation(a)
        this.clearSelection()
    },
    removeHighlight(cfi) {
        const a = annotations.get(cfi)
        if (a) { view.deleteAnnotation(a); annotations.delete(cfi) }
    },
    async search(query) {
        view.clearSearch()
        if (!query) return A.onSearchDone()
        let count = 0
        for await (const r of view.search({ query, matchCase: false, matchDiacritics: false, matchWholeWords: false })) {
            if (r === 'done') break
            if (r.subitems) {
                for (const s of r.subitems) {
                    if (count++ > 300) break
                    send('onSearchResult', { label: r.label ?? '', cfi: s.cfi, pre: s.excerpt?.pre ?? '', match: s.excerpt?.match ?? '', post: s.excerpt?.post ?? '' })
                }
            } else if (r.progress != null) A.onSearchProgress(r.progress)
        }
        A.onSearchDone()
    },
    clearSearch: () => view?.clearSearch(),

    /**
     * Speed reading (RSVP). Sends Worm.onRsvp({section, start, words}) with the words of section
     * (current + ahead); for the current one, start is the word at the top of the page.
     */
    async rsvpWords(ahead) {
        try {
            const index = lastIndex + (ahead | 0)
            const section = view.book.sections[index]
            if (!section) return send('onRsvp', { section: index, start: 0, words: [], end: true })
            const doc = await section.createDocument()
            const { words } = walkWords(doc)
            let start = 0
            if (!ahead && lastRange) start = wordsBefore(lastRange.startContainer.ownerDocument, lastRange.startContainer, lastRange.startOffset)
            send('onRsvp', { section: index, start, words, end: index >= view.book.sections.length - 1 })
        } catch (e) { A.log('rsvp: ' + e); send('onRsvp', { section: -1, start: 0, words: [], end: true }) }
    },
    /** Leave speed reading: turn the page to wherever you stopped. */
    async rsvpSeek(index, word) {
        try {
            const doc = await view.book.sections[index].createDocument()
            const { nodes } = walkWords(doc)
            const hit = nodes.find(n => word < n.first + n.count) ?? nodes[nodes.length - 1]
            if (!hit) return
            const r = doc.createRange()
            r.setStart(hit.node, hit.offsets[Math.max(0, word - hit.first)] ?? 0)
            r.collapse(true)
            await view.goTo(view.getCFI(index, r))
        } catch (e) { A.log('rsvp seek: ' + e) }
    },
    /** Measures where a CFI or XPointer sits in the book and reports back via Worm.onMeasured(id, fraction, cfi). */
    async measure(id, target) {
        try {
            const cfi = target.startsWith('/body') ? await fromXPointer(target) : target
            A.onMeasured(id, cfi ? await fractionAtCFI(cfi) : -1, cfi ?? '')
        } catch (e) { A.log('measure: ' + e); A.onMeasured(id, -1, '') }
    },
}

A.onBridge()

// ------------------------------------------------------------------ speed reading helpers
const SKIP = new Set(['SCRIPT', 'STYLE', 'HEAD', 'TITLE', 'NAV', 'ASIDE', 'RT'])
function textNodes(doc) {
    const out = []
    const w = doc.createTreeWalker(doc.body ?? doc.documentElement, NodeFilter.SHOW_TEXT, {
        acceptNode: n => {
            for (let p = n.parentNode; p; p = p.parentNode) if (SKIP.has(p.nodeName?.toUpperCase())) return NodeFilter.FILTER_REJECT
            return NodeFilter.FILTER_ACCEPT
        },
    })
    for (let n = w.nextNode(); n; n = w.nextNode()) out.push(n)
    return out
}
/** Every word in reading order, plus where each text node's words start (for jumping back). */
function walkWords(doc) {
    const words = [], nodes = []
    for (const node of textNodes(doc)) {
        const re = /\S+/g, offsets = []
        let m, first = words.length
        while ((m = re.exec(node.data))) { words.push(m[0]); offsets.push(m.index) }
        if (offsets.length) nodes.push({ node, first, count: offsets.length, offsets })
    }
    return { words, nodes }
}
/** How many words come before (node, offset) in this document. */
function wordsBefore(doc, node, offset) {
    let n = 0
    for (const t of textNodes(doc)) {
        if (t === node) return n + (t.data.slice(0, offset).match(/\S+/g)?.length ?? 0)
        if (node.nodeType !== 3 && node.contains?.(t) && node.childNodes[offset]?.contains?.(t)) return n
        n += t.data.match(/\S+/g)?.length ?? 0
    }
    return 0
}
