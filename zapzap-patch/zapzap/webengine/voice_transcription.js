{qwebchannel_js_code}

(() => {
    "use strict";

    const LOG = "[Press-to-Talk]";
    const SCAN_MS = 2000;
    const BRIDGE_RETRY_MS = 2000;
    const PROCESSED = new Set();
    const PENDING = new Set();

    let bridge = null;
    let booted = false;
    let scanScheduled = false;
    let mutationPaused = false;

    function log(msg) {
        console.info(LOG, msg);
        try {
            if (bridge && typeof bridge.pttLog === "function") {
                bridge.pttLog(String(msg));
            }
        } catch (_) {}
    }

    function injectStyles() {
        if (document.getElementById("ptt-transcript-styles")) {
            return;
        }
        const style = document.createElement("style");
        style.id = "ptt-transcript-styles";
        style.textContent = `
            [data-ptt-host] {
                overflow: hidden;
                box-sizing: border-box;
            }
            .ptt-transcript {
                display: flex;
                align-items: flex-start;
                gap: 8px;
                margin: 4px 0 2px 0;
                padding: 6px 10px;
                border-radius: 8px;
                font-size: 13.6px;
                line-height: 1.4;
                word-wrap: break-word;
                overflow-wrap: break-word;
                white-space: pre-wrap;
                box-sizing: border-box;
            }
            .ptt-transcript--loading {
                color: #8696a0;
            }
            .ptt-transcript--error {
                color: #ea0038;
                font-style: italic;
            }
            .ptt-transcript--text {
                color: #e9edef;
                background: rgba(11, 20, 26, 0.45);
                border-radius: 6px;
                padding: 6px 10px;
            }
            .ptt-spinner {
                width: 14px;
                height: 14px;
                border: 2px solid rgba(134, 150, 160, 0.35);
                border-top-color: #25d366;
                border-radius: 50%;
                animation: ptt-spin 0.75s linear infinite;
                flex-shrink: 0;
                margin-top: 1px;
            }
            @keyframes ptt-spin {
                to { transform: rotate(360deg); }
            }
        `;
        document.head.appendChild(style);
    }

    function isVoiceMessageRow(row) {
        if (row.querySelector("canvas")) {
            return true;
        }
        return false;
    }

    function findVoiceRows() {
        const rows = [];
        for (const row of document.querySelectorAll("[data-id]")) {
            const msgId = row.getAttribute("data-id");
            if (!msgId || PROCESSED.has(msgId) || PENDING.has(msgId)) {
                continue;
            }
            if (!isVoiceMessageRow(row)) {
                continue;
            }
            rows.push({ msgId, row });
        }
        return rows;
    }

    function findMessageRow(msgId) {
        for (const row of document.querySelectorAll("[data-id]")) {
            if (row.getAttribute("data-id") === msgId && isVoiceMessageRow(row)) {
                return row;
            }
        }
        return null;
    }

    function setTranscriptUi(msgId, text, kind) {
        const row = findMessageRow(msgId);
        if (!row) {
            return;
        }
        injectStyles();

        let host = row.querySelector("[data-ptt-host]");
        if (!host) {
            mutationPaused = true;
            host = document.createElement("div");
            host.setAttribute("data-ptt-host", "1");
            // Walk up from canvas but stay WITHIN the row to find the audio container
            const canvas = row.querySelector("canvas");
            if (canvas) {
                let container = canvas;
                while (container.parentElement && container.parentElement !== row) {
                    container = container.parentElement;
                }
                // Insert host right after the audio container, as a sibling
                container.after(host);
                // Match the audio container's width
                const w = container.getBoundingClientRect().width;
                if (w > 0) host.style.maxWidth = w + "px";
            } else {
                row.appendChild(host);
            }
        }

        let box = host.querySelector(".ptt-transcript");
        if (!box) {
            box = document.createElement("div");
            box.className = "ptt-transcript";
            host.appendChild(box);
        }

        box.className = "ptt-transcript";
        box.innerHTML = "";

        if (kind === "loading") {
            box.classList.add("ptt-transcript--loading");
            const spinner = document.createElement("div");
            spinner.className = "ptt-spinner";
            const label = document.createElement("span");
            label.textContent = text;
            box.appendChild(spinner);
            box.appendChild(label);
        } else if (kind === "error") {
            box.classList.add("ptt-transcript--error");
            box.textContent = text;
        } else {
            box.classList.add("ptt-transcript--text");
            box.textContent = text;
        }

        setTimeout(() => { mutationPaused = false; }, 50);
    }

    window.__pttDeliverTranscript = (payload) => {
        const msgId = payload?.msgId ?? payload?.[0];
        const text = payload?.text ?? payload?.[1] ?? "";
        if (!msgId) {
            return;
        }
        PENDING.delete(msgId);
        PROCESSED.add(msgId);
        setTranscriptUi(msgId, text, "text");
        log(`delivered ${msgId.slice(0, 20)}… (${text.length}ch)`);
    };

    function extractMsgIdVariants(dataId) {
        const ids = [dataId];
        if (dataId.includes("_")) {
            const parts = dataId.split("_");
            const tail = parts[parts.length - 1];
            if (tail) ids.push(tail);
            if (parts.length >= 3) {
                const serialized = parts.slice(0, 3).join("_");
                if (serialized !== dataId) ids.push(serialized);
            }
        }
        return [...new Set(ids)];
    }

    async function resolveMessage(msgId) {
        const variants = extractMsgIdVariants(msgId);
        try {
            const Collections = require("WAWebCollections");

            for (const v of variants) {
                const msg = Collections.Msg.get(v);
                if (msg) return msg;
            }

            for (const v of variants) {
                try {
                    const fetched = await Collections.Msg.getMessagesById([v]);
                    if (fetched?.messages?.[0]) return fetched.messages[0];
                } catch (_) {}
            }

            const models = Collections.Msg?.models || Collections.Msg?._models || [];
            for (const candidate of models) {
                const serialized = candidate?.id?._serialized;
                const bare = candidate?.id?.id;
                for (const v of variants) {
                    if (serialized === v || bare === v) return candidate;
                }
            }
        } catch (e) {
            log(`resolveMessage error: ${e}`);
        }
        return null;
    }

    async function arrayBufferToBase64(buffer) {
        const bytes = new Uint8Array(buffer);
        const chunkSize = 8192;
        let binary = "";
        for (let i = 0; i < bytes.length; i += chunkSize) {
            binary += String.fromCharCode.apply(
                null,
                bytes.subarray(i, i + chunkSize)
            );
        }
        return btoa(binary);
    }

    async function downloadVoiceAudio(msg) {
        if (!msg || !isAudioType(msg.type)) return null;
        if (!msg.mediaData) return null;
        if (msg.mediaData.mediaStage === "REUPLOADING") return null;

        if (msg.mediaData.mediaStage !== "RESOLVED" && typeof msg.downloadMedia === "function") {
            await msg.downloadMedia({ downloadEvenIfExpensive: true, rmrReason: 1 });
        }

        const stage = msg.mediaData.mediaStage || "";
        if (stage.includes("ERROR") || stage === "FETCHING") return null;

        const mockQpl = {
            addAnnotations() { return this; },
            addPoint() { return this; },
        };

        const downloadManager = require("WAWebDownloadManager").downloadManager;
        const dl = downloadManager.downloadAndMaybeDecrypt || downloadManager.downloadAndDecrypt;
        return await dl.call(downloadManager, {
            directPath: msg.directPath,
            encFilehash: msg.encFilehash,
            filehash: msg.filehash,
            mediaKey: msg.mediaKey,
            mediaKeyTimestamp: msg.mediaKeyTimestamp,
            type: msg.type,
            signal: new AbortController().signal,
            downloadQpl: mockQpl,
        });
    }

    function isAudioType(type) {
        return type === "ptt" || type === "audio";
    }

    async function processMessage(msgId) {
        if (!bridge || PROCESSED.has(msgId) || PENDING.has(msgId)) return;
        PENDING.add(msgId);

        // Ask Python to check file cache — if hit, it delivers via __pttDeliverTranscript
        bridge.checkCache(msgId);
        await new Promise(r => setTimeout(r, 200));
        if (PROCESSED.has(msgId)) return;

        setTranscriptUi(msgId, "Transcribing…", "loading");

        try {
            const msg = await resolveMessage(msgId);
            if (!msg) {
                log(`no msg in store for ${msgId.slice(0, 24)}`);
                setTranscriptUi(msgId, "Message not in store", "error");
                PENDING.delete(msgId);
                PROCESSED.add(msgId);
                return;
            }

            if (!isAudioType(msg.type)) {
                PENDING.delete(msgId);
                PROCESSED.add(msgId);
                const host = findMessageRow(msgId)?.querySelector("[data-ptt-host]");
                if (host) host.remove();
                return;
            }

            setTranscriptUi(msgId, "Downloading audio…", "loading");
            const audioBuffer = await downloadVoiceAudio(msg);
            if (!audioBuffer) {
                setTranscriptUi(msgId, "Could not download audio", "error");
                PENDING.delete(msgId);
                return;
            }

            setTranscriptUi(msgId, "Transcribing…", "loading");
            const base64 = await arrayBufferToBase64(audioBuffer);
            log(`sending ${msgId.slice(0, 20)}… (${base64.length} b64 chars)`);
            bridge.requestTranscription(msgId, base64);
        } catch (err) {
            console.error(LOG, "failed for", msgId, err);
            setTranscriptUi(msgId, `Error: ${err.message || err}`, "error");
            PENDING.delete(msgId);
            log(`error ${msgId.slice(0, 20)}…: ${err}`);
        }
    }

    const MAX_CONCURRENT = 2;
    const QUEUE = [];
    let activeCount = 0;

    function drainQueue() {
        while (activeCount < MAX_CONCURRENT && QUEUE.length > 0) {
            const msgId = QUEUE.shift();
            if (PROCESSED.has(msgId) || PENDING.has(msgId)) continue;
            activeCount++;
            processMessage(msgId).finally(() => {
                activeCount--;
                drainQueue();
            });
        }
    }

    function enqueue(msgId) {
        if (!bridge || PROCESSED.has(msgId) || PENDING.has(msgId)) return;
        if (QUEUE.includes(msgId)) return;
        QUEUE.push(msgId);
        drainQueue();
    }

    function pruneStaleProcessed() {
        for (const msgId of PROCESSED) {
            if (!findMessageRow(msgId)) {
                PROCESSED.delete(msgId);
            }
        }
    }

    function restoreCached() {
        for (const row of document.querySelectorAll("[data-id]")) {
            const msgId = row.getAttribute("data-id");
            if (!msgId || PENDING.has(msgId)) continue;
            if (!isVoiceMessageRow(row)) continue;
            if (row.querySelector("[data-ptt-host]")) continue;
            if (PROCESSED.has(msgId)) {
                PROCESSED.delete(msgId);
            }
        }
    }

    function scanVisibleVoiceMessages() {
        if (!bridge) return;
        pruneStaleProcessed();
        restoreCached();
        const rows = findVoiceRows();
        if (rows.length > 0) {
            log(`scan: ${rows.length} new voice msg(s), active=${activeCount}, queued=${QUEUE.length}`);
        }
        for (const { msgId } of rows) {
            enqueue(msgId);
        }
    }

    function scheduleScan() {
        if (scanScheduled) return;
        scanScheduled = true;
        setTimeout(() => {
            scanScheduled = false;
            scanVisibleVoiceMessages();
        }, 300);
    }

    function setupBridge() {
        if (bridge) return;
        if (typeof QWebChannel === "undefined" || typeof qt === "undefined" || !qt.webChannelTransport) {
            return;
        }
        try {
            new QWebChannel(qt.webChannelTransport, (channel) => {
                bridge = channel.objects && channel.objects.zapZapBridge;
                if (bridge) {
                    log("bridge connected");
                    scanVisibleVoiceMessages();
                } else {
                    log("zapZapBridge not on channel");
                }
            });
        } catch (err) {
            console.warn(LOG, "QWebChannel error", err);
        }
    }

    function waitForWhatsApp() {
        try {
            require("WAWebCollections");
            return true;
        } catch (_) {
            return false;
        }
    }

    function startObservers() {
        const chatPane =
            document.querySelector("#main") ||
            document.querySelector('[data-testid="conversation-panel-body"]') ||
            document.body;

        const observer = new MutationObserver(() => {
            if (!mutationPaused) {
                scheduleScan();
            }
        });
        observer.observe(chatPane, { childList: true, subtree: true });
    }

    function boot() {
        injectStyles();
        setupBridge();

        if (!waitForWhatsApp()) {
            setTimeout(boot, 1000);
            return;
        }

        if (booted) {
            scanVisibleVoiceMessages();
            return;
        }

        booted = true;
        log("voice transcription active");
        setInterval(setupBridge, BRIDGE_RETRY_MS);
        scanVisibleVoiceMessages();
        setInterval(scanVisibleVoiceMessages, SCAN_MS);
        startObservers();
    }

    window.__pttBoot = boot;

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }
})();
