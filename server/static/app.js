/* OneAi browser simulator — mic + camera + TTS + debug UI */

const MAGIC = 0x4f4e4541; // ONEA
const VERSION = 1;
const BIN_MIC = 1;
const BIN_TTS = 2;
const BIN_JPEG = 3;

const els = {
  connDot: document.getElementById("connDot"),
  connLabel: document.getElementById("connLabel"),
  sessionId: document.getElementById("sessionId"),
  rtt: document.getElementById("rtt"),
  pipeline: document.getElementById("pipeline"),
  clientList: document.getElementById("clientList"),
  localVideo: document.getElementById("localVideo"),
  overlay: document.getElementById("overlay"),
  camToggle: document.getElementById("camToggle"),
  micToggle: document.getElementById("micToggle"),
  detCount: document.getElementById("detCount"),
  ptt: document.getElementById("ptt"),
  sttLog: document.getElementById("sttLog"),
  llmLog: document.getElementById("llmLog"),
  emotion: document.getElementById("emotion"),
  eventLog: document.getElementById("eventLog"),
};

let ws = null;
let sessionId = null;
let mediaStream = null;
let audioCtx = null;
let processor = null;
let micSource = null;
let listening = false;
let lastBoxes = [];
let videoTimer = null;
let reconnectTimer = null;

function logEvent(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}\n`;
  els.eventLog.textContent = (line + els.eventLog.textContent).slice(0, 4000);
}

function setConn(state, label) {
  els.connDot.className = "dot " + state;
  els.connLabel.textContent = label;
}

function packBinary(type, payload) {
  const buf = new ArrayBuffer(8 + payload.byteLength);
  const view = new DataView(buf);
  view.setUint32(0, MAGIC);
  view.setUint8(4, VERSION);
  view.setUint8(5, type);
  view.setUint16(6, 0);
  new Uint8Array(buf, 8).set(new Uint8Array(payload));
  return buf;
}

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${location.host}/ws`;
}

async function ensureMedia() {
  if (mediaStream) return mediaStream;
  mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      sampleRate: { ideal: 16000 },
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
    video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } },
  });
  els.localVideo.srcObject = mediaStream;
  await els.localVideo.play().catch(() => {});
  return mediaStream;
}

function floatTo16BitPCM(float32) {
  const out = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i++) {
    const s = Math.max(-1, Math.min(1, float32[i]));
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return out.buffer;
}

/** Linear resample — nearest-neighbor làm Whisper loạn chữ. */
function downsample(buffer, inRate, outRate) {
  if (Math.abs(inRate - outRate) < 1) return buffer;
  const ratio = inRate / outRate;
  const newLen = Math.floor(buffer.length / ratio);
  const result = new Float32Array(newLen);
  for (let i = 0; i < newLen; i++) {
    const pos = i * ratio;
    const idx = Math.floor(pos);
    const frac = pos - idx;
    const a = buffer[idx] || 0;
    const b = buffer[Math.min(idx + 1, buffer.length - 1)] || 0;
    result[i] = a + (b - a) * frac;
  }
  return result;
}

/** Giữ mic pipeline suốt phiên — chỉ gate khi đang giữ PTT (tránh cắt đầu câu). */
async function ensureMicPipeline() {
  await ensureMedia();
  if (processor) {
    if (audioCtx && audioCtx.state === "suspended") await audioCtx.resume();
    return;
  }
  audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
  await audioCtx.resume();
  micSource = audioCtx.createMediaStreamSource(mediaStream);
  // 2048 ~ nhỏ hơn latency, đủ ổn định cho STT
  processor = audioCtx.createScriptProcessor(2048, 1, 1);
  const mute = audioCtx.createGain();
  mute.gain.value = 0;
  processor.onaudioprocess = (e) => {
    if (!listening || !ws || ws.readyState !== WebSocket.OPEN) return;
    if (!els.micToggle.checked) return;
    const input = e.inputBuffer.getChannelData(0);
    const down = downsample(input, audioCtx.sampleRate, 16000);
    if (down.length < 8) return;
    ws.send(packBinary(BIN_MIC, floatTo16BitPCM(down)));
  };
  micSource.connect(processor);
  processor.connect(mute);
  mute.connect(audioCtx.destination);
  logEvent("mic pipeline ready @ " + audioCtx.sampleRate + " Hz");
}

function stopMicCapture() {
  // Không teardown mỗi lần thả PTT — chỉ dừng gửi bằng flag `listening`.
}

function startVideoLoop() {
  stopVideoLoop();
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  videoTimer = setInterval(() => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (!els.camToggle.checked || !els.localVideo.videoWidth) return;
    const w = els.localVideo.videoWidth;
    const h = els.localVideo.videoHeight;
    canvas.width = w;
    canvas.height = h;
    ctx.drawImage(els.localVideo, 0, 0, w, h);
    canvas.toBlob(
      (blob) => {
        if (!blob) return;
        blob.arrayBuffer().then((ab) => {
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(packBinary(BIN_JPEG, ab));
          }
        });
      },
      "image/jpeg",
      0.7
    );
  }, 140);
}

function stopVideoLoop() {
  if (videoTimer) {
    clearInterval(videoTimer);
    videoTimer = null;
  }
}

function drawOverlay() {
  const video = els.localVideo;
  const canvas = els.overlay;
  const rect = video.getBoundingClientRect();
  canvas.width = rect.width * devicePixelRatio;
  canvas.height = rect.height * devicePixelRatio;
  canvas.style.width = rect.width + "px";
  canvas.style.height = rect.height + "px";
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.scale(devicePixelRatio, devicePixelRatio);

  const vw = video.videoWidth || 1;
  const vh = video.videoHeight || 1;
  const scale = Math.min(rect.width / vw, rect.height / vh);
  const dw = vw * scale;
  const dh = vh * scale;
  const ox = (rect.width - dw) / 2;
  const oy = (rect.height - dh) / 2;

  ctx.lineWidth = 2;
  ctx.font = "12px monospace";
  for (const b of lastBoxes) {
    const x = ox + b.x * dw;
    const y = oy + b.y * dh;
    const w = b.w * dw;
    const h = b.h * dh;
    ctx.strokeStyle = "#3ecf8e";
    ctx.fillStyle = "rgba(62,207,142,0.15)";
    ctx.fillRect(x, y, w, h);
    ctx.strokeRect(x, y, w, h);
    const label = `${b.label} ${(b.conf * 100).toFixed(0)}%`;
    ctx.fillStyle = "#3ecf8e";
    ctx.fillText(label, x + 4, Math.max(12, y - 4));
  }
}

async function playTtsBytes(arrayBuffer) {
  const bytes = new Uint8Array(arrayBuffer);
  // Skip ONEA header already stripped by caller — payload only
  const blob = new Blob([bytes], { type: guessAudioMime(bytes) });
  const url = URL.createObjectURL(blob);
  const audio = new Audio(url);
  try {
    await audio.play();
  } catch (e) {
    logEvent("TTS play error: " + e.message);
  }
  audio.onended = () => URL.revokeObjectURL(url);
}

function guessAudioMime(bytes) {
  if (bytes.length >= 4 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46) {
    return "audio/wav";
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0) {
    return "audio/mpeg";
  }
  if (bytes.length >= 3 && bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33) {
    return "audio/mpeg";
  }
  return "audio/mpeg";
}

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  setConn("warn", "Đang kết nối…");
  ws = new WebSocket(wsUrl());
  ws.binaryType = "arraybuffer";

  ws.onopen = () => {
    setConn("on", "Đã kết nối");
    logEvent("WebSocket open");
    ws.send(
      JSON.stringify({
        type: "hello",
        client_id: "browser-" + Math.random().toString(36).slice(2, 8),
        role: "phone",
        caps: { audio: true, video: true },
      })
    );
  };

  ws.onclose = () => {
    setConn("off", "Mất kết nối");
    sessionId = null;
    els.sessionId.textContent = "—";
    logEvent("WebSocket closed — reconnect in 2s");
    stopVideoLoop();
    listening = false;
    els.ptt.classList.remove("armed");
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 2000);
  };

  ws.onerror = () => logEvent("WebSocket error");

  ws.onmessage = async (ev) => {
    if (typeof ev.data === "string") {
      handleJson(JSON.parse(ev.data));
      return;
    }
    const buf = ev.data;
    const view = new DataView(buf);
    const magic = view.getUint32(0);
    if (magic !== MAGIC) return;
    const type = view.getUint8(5);
    const payload = buf.slice(8);
    if (type === BIN_TTS) {
      await playTtsBytes(payload);
    }
  };
}

function handleJson(msg) {
  switch (msg.type) {
    case "hello_ack":
      sessionId = msg.session_id;
      els.sessionId.textContent = sessionId.slice(0, 8);
      logEvent("hello_ack " + sessionId);
      ensureMedia()
        .then(() => ensureMicPipeline())
        .then(() => {
          startVideoLoop();
        })
        .catch((e) => logEvent("Media error: " + e.message));
      break;
    case "ping":
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "pong", t: msg.t }));
      }
      break;
    case "status":
      els.pipeline.textContent = msg.pipeline || "idle";
      if (msg.pipeline_detail) {
        els.pipeline.textContent += " · " + msg.pipeline_detail;
      }
      const me = (msg.clients || []).find((c) => c.session_id === sessionId);
      if (me && me.last_rtt_ms != null) {
        els.rtt.textContent = String(me.last_rtt_ms);
        setConn(me.stable ? "on" : "warn", me.stable ? "Ổn định" : "Không ổn định");
      }
      els.clientList.textContent = (msg.clients || [])
        .map((c) => `${c.client_id}${c.stable ? "" : "⚠"}`)
        .join(", ");
      break;
    case "stt":
      els.sttLog.textContent = msg.text || "";
      break;
    case "llm":
      els.llmLog.textContent = msg.text || "";
      els.emotion.textContent = msg.emotion || "—";
      break;
    case "tts":
      logEvent("tts " + msg.state);
      break;
    case "detections":
      lastBoxes = msg.boxes || [];
      els.detCount.textContent = `${lastBoxes.length} objects`;
      drawOverlay();
      break;
    case "error":
      logEvent("ERROR: " + msg.message);
      break;
    default:
      break;
  }
}

async function beginListen() {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  await ensureMicPipeline();
  listening = true;
  els.ptt.classList.add("armed");
  els.ptt.textContent = "Đang nghe… thả ra để gửi";
  // Nhỏ delay để bỏ tiếng click nút đầu câu
  await new Promise((r) => setTimeout(r, 80));
  if (!listening) return;
  ws.send(JSON.stringify({ type: "listen", state: "start" }));
  logEvent("listen start");
}

function endListen() {
  if (!listening) return;
  listening = false;
  els.ptt.classList.remove("armed");
  els.ptt.textContent = "Giữ để nói";
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: "listen", state: "stop" }));
  }
  logEvent("listen stop — processing");
}

els.ptt.addEventListener("mousedown", (e) => {
  e.preventDefault();
  beginListen();
});
els.ptt.addEventListener("mouseup", endListen);
els.ptt.addEventListener("mouseleave", endListen);
els.ptt.addEventListener("touchstart", (e) => {
  e.preventDefault();
  beginListen();
}, { passive: false });
els.ptt.addEventListener("touchend", endListen);

els.camToggle.addEventListener("change", async () => {
  if (!mediaStream) return;
  mediaStream.getVideoTracks().forEach((t) => (t.enabled = els.camToggle.checked));
});

els.micToggle.addEventListener("change", () => {
  if (!mediaStream) return;
  mediaStream.getAudioTracks().forEach((t) => (t.enabled = els.micToggle.checked));
});

window.addEventListener("resize", drawOverlay);
connect();
