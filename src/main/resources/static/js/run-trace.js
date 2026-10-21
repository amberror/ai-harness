// Live run trace.
//
// Kept as an external file rather than inline in the template: the page is rendered through a
// Thymeleaf layout fragment that passes only the <section> through, so a <script> sibling in the
// body is silently discarded. An external file also keeps this out of reach of template inlining,
// which would otherwise try to interpret the JavaScript.
//
// Loaded on every page and self-guarding: it does nothing unless the page carries a live run.
(function () {
    const root = document.querySelector('section[data-run-id]');
    if (!root) return;
    const runId = root.dataset.runId;
    const live = root.dataset.live === 'true';
    if (!live) return;

    const timeline = document.getElementById('timeline');
    const reasoningPane = document.getElementById('live-reasoning');
    const contentPane = document.getElementById('live-content');
    const statusBadge = document.getElementById('run-status');
    const liveDot = document.getElementById('live-dot');

    // The journal was rendered server-side and the stream replays from the beginning, so an event
    // can legitimately arrive twice. Every journalled event carries a unique seq; live-only deltas
    // carry -1 and are never deduplicated, because each one is genuinely distinct.
    const seen = new Set(
        Array.from(timeline.querySelectorAll('.event[data-seq]')).map(el => el.dataset.seq));

    const TERMINAL = new Set(['RUN_FINISHED', 'RUN_FAILED']);

    function prettyPayload(payload) {
        if (payload === null || payload === undefined) return '';
        let text = JSON.stringify(payload, null, 2);
        // Raw request and response bodies are stored as strings so the exact bytes survive. Show
        // them parsed where possible, since an escaped one-line blob is unreadable.
        if (payload.body) {
            try {
                text += '\n\n--- body, parsed ---\n' + JSON.stringify(JSON.parse(payload.body), null, 2);
            } catch (e) { /* SSE streams and error bodies are not JSON; the raw form above stands */ }
        }
        return text;
    }

    function appendEvent(evt) {
        const seq = String(evt.seq);
        if (evt.seq >= 0) {
            if (seen.has(seq)) return;
            seen.add(seq);
        }

        const article = document.createElement('article');
        article.className = 'event event-new';
        if (evt.seq >= 0) article.dataset.seq = seq;

        const head = document.createElement('div');
        head.className = 'event-head';
        const parts = [
            ['event-seq', evt.seq >= 0 ? evt.seq : '·'],
            ['event-type', evt.type],
            ['subtle', 'step ' + evt.stepNo]
        ];
        if (evt.durationMs !== null && evt.durationMs !== undefined) {
            parts.push(['subtle', evt.durationMs + ' ms']);
        }
        for (const [cls, text] of parts) {
            const span = document.createElement('span');
            span.className = cls;
            span.textContent = text;
            head.appendChild(span);
        }
        article.appendChild(head);

        if (evt.payload) {
            const details = document.createElement('details');
            const summary = document.createElement('summary');
            summary.textContent = 'payload';
            const pre = document.createElement('pre');
            pre.className = 'payload';
            pre.textContent = prettyPayload(evt.payload);
            details.appendChild(summary);
            details.appendChild(pre);
            article.appendChild(details);
        }
        timeline.appendChild(article);
    }

    const source = new EventSource('/api/runs/' + runId + '/stream');

    source.onopen = () => liveDot && liveDot.classList.add('pulse-on');
    source.onerror = () => liveDot && liveDot.classList.remove('pulse-on');

    // Deltas are the only high-frequency events, and they belong in the live panes rather than the
    // timeline: one timeline row per token would bury everything that actually matters.
    source.addEventListener('REASONING_DELTA', e => {
        reasoningPane.textContent += JSON.parse(e.data).payload.text;
        reasoningPane.scrollTop = reasoningPane.scrollHeight;
    });
    source.addEventListener('CONTENT_DELTA', e => {
        contentPane.textContent += JSON.parse(e.data).payload.text;
        contentPane.scrollTop = contentPane.scrollHeight;
    });

    for (const type of ['RUN_STARTED', 'MODEL_REQUEST', 'MODEL_RESPONSE', 'OUTPUT_TRUNCATED',
                        'TOOL_CALL_PARSED', 'TOOL_CALL_INVALID', 'TOOL_EXEC_STARTED',
                        'TOOL_EXEC_SUCCEEDED', 'TOOL_EXEC_FAILED', 'CONTEXT_COMPACTED',
                        'LOOP_DETECTED', 'BUDGET_EXCEEDED', 'RUN_FINISHED', 'RUN_FAILED']) {
        source.addEventListener(type, e => {
            const evt = JSON.parse(e.data);
            appendEvent(evt);
            if (TERMINAL.has(evt.type)) {
                if (statusBadge && evt.payload && evt.payload.status) {
                    statusBadge.textContent = evt.payload.status;
                }
                source.close();
                liveDot && liveDot.classList.remove('pulse-on');
                // Reload once so the server-rendered transcript, totals and final answer catch up
                // with what the stream just showed.
                setTimeout(() => window.location.reload(), 600);
            }
        });
    }
})();
