/* beamer-card – Beamer (Media Bridge): Bild/App-Logo, Fortschritt, Steuerung, Lautstärke, Apps */
/* Getönt-flache Flächen – gleiches Schema wie die Aktions-Knöpfe im restlichen Dashboard */
const BMC_TINT = (c, p = 16) => `color-mix(in srgb, var(--${c}-color) ${p}%, var(--card-background-color))`;
const BMC_STATES = {
  playing: { label: "Läuft", icon: "mdi:play", c: "pink" },
  paused: { label: "Pausiert", icon: "mdi:pause", c: "orange" },
  buffering: { label: "Lädt", icon: "mdi:loading", c: "pink" },
  idle: { label: "Bereit", icon: "mdi:projector", c: "grey" },
  on: { label: "An", icon: "mdi:projector", c: "grey" },
  off: { label: "Aus", icon: "mdi:projector-off", c: "grey" },
  unavailable: { label: "Nicht erreichbar", icon: "mdi:lan-disconnect", c: "grey" },
};
const BMC_MODES = { standard: "Standard", soft: "Weich", movie: "Film", office: "Büro", dynamic: "Dynamisch", vivid: "Lebhaft" };
const BMC_F = { PAUSE: 1, SEEK: 2, VOLUME_SET: 4, VOLUME_MUTE: 8, PREVIOUS: 16, NEXT: 32, TURN_ON: 128, TURN_OFF: 256 };
const BMC_LOGOS = new Map(); // "entity|app" -> Promise<signed url | null>

class BeamerCard extends HTMLElement {
  setConfig(config) {
    if (!config.entity) throw new Error("entity fehlt");
    const base = config.entity.split(".")[1];
    this._config = {
      apps: ["Netflix", "YouTube", "Prime Video", "ARD Mediathek"],
      end: `sensor.${base}_medienende`,
      next: `sensor.${base}_als_nachstes`,
      sleep: `switch.${base}_nach_diesem_titel_ausschalten`,
      picture_mode: `select.${base}_bildmodus`,
      screensaver: `binary_sensor.${base}_bildschirmschoner`,
      ...config,
    };
    this._modesOpen = false;
    if (!this.shadowRoot) this.attachShadow({ mode: "open" });
  }

  connectedCallback() {
    clearInterval(this._tick);
    this._tick = setInterval(() => this._progress(), 1000);
  }
  disconnectedCallback() { clearInterval(this._tick); }

  set hass(hass) {
    this._hass = hass;
    const c = this._config;
    const m = hass.states[c.entity];
    const a = m ? m.attributes : {};
    const sig = [m && m.state, a.media_title, a.media_artist, a.app_name, a.entity_picture, a.volume_level, a.is_volume_muted,
      a.media_position_updated_at, a.media_duration, (a.source_list || []).length,
      ...[c.end, c.next, c.sleep, c.picture_mode, c.screensaver].map((i) => hass.states[i] && hass.states[i].state)].join("|");
    if (sig === this._sig || this._dragging) return;
    this._sig = sig;
    this._render();
  }

  getCardSize() { return 7; }
  getGridOptions() { return { columns: 12, rows: "auto", min_columns: 6 }; }

  _s(id) { const s = this._hass.states[id]; return s ? s.state : undefined; }
  _ok(v) { return v !== undefined && v !== "unavailable" && v !== "unknown" && v !== ""; }
  _esc(t) { return String(t ?? "").replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch])); }
  _fmt(sec) {
    sec = Math.max(0, Math.round(sec));
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return h ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
  }
  _hm(d) { return d.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }); }

  _position() {
    const m = this._hass.states[this._config.entity];
    if (!m) return null;
    const a = m.attributes;
    if (a.media_duration == null || a.media_position == null) return null;
    let pos = a.media_position;
    if (m.state === "playing" && a.media_position_updated_at) {
      pos += (Date.now() - new Date(a.media_position_updated_at).getTime()) / 1000;
    }
    return { pos: Math.min(pos, a.media_duration), dur: a.media_duration };
  }

  _progress() {
    const r = this.shadowRoot;
    const p = this._hass && this._position();
    const bar = r && r.querySelector(".bar i");
    if (!p || !bar) return;
    bar.style.width = `${(100 * p.pos) / p.dur}%`;
    r.querySelector(".t-pos").textContent = this._fmt(p.pos);
    r.querySelector(".t-rest").textContent = `−${this._fmt(p.dur - p.pos)}`;
  }

  _logo(app, img) {
    const key = `${this._config.entity}|${app}`;
    if (!BMC_LOGOS.has(key)) {
      const path = `/api/media_bridge/icon/${this._config.entity}?app=${encodeURIComponent(app)}`;
      BMC_LOGOS.set(key, this._hass
        .callWS({ type: "auth/sign_path", path, expires: 7 * 86400 })
        .then((r) => r.path)
        .catch(() => null));
    }
    BMC_LOGOS.get(key).then((url) => {
      if (url) img.src = url;
      else img.closest(".app")?.classList.add("noimg");
    });
  }

  _render() {
    if (!this._hass || !this._config) return;
    const c = this._config;
    const h = this._hass;
    const m = h.states[c.entity];
    let st = m ? m.state : "unavailable";
    if (!BMC_STATES[st]) st = "on";
    const a = m ? m.attributes : {};
    const feat = a.supported_features || 0;
    const has = (f) => (feat & f) === f;
    const off = st === "off" || st === "unavailable";
    const media = ["playing", "paused", "buffering"].includes(st);
    const dreaming = this._s(c.screensaver) === "on";
    const S = dreaming && !media ? { label: "Bildschirmschoner", icon: "mdi:weather-night", c: "indigo" } : BMC_STATES[st];

    const pic = !off && a.entity_picture ? a.entity_picture : null;
    const isLogo = pic && pic.includes("cache=app-");
    const title = media ? a.media_title || a.app_name || "Wiedergabe" : off ? "Beamer" : a.app_name || "Startbildschirm";
    const sub = media ? [a.media_artist, a.media_title ? a.app_name : null].filter(Boolean).join(" · ") : "";

    const p = media ? this._position() : null;
    const endRaw = this._s(c.end);
    const end = this._ok(endRaw) ? new Date(endRaw) : null;
    const next = this._s(c.next);

    // Apps: nur die, die der Beamer auch kennt
    const sources = a.source_list || [];
    const apps = c.apps.filter((x) => sources.includes(x));
    const curApp = a.app_name || a.source;

    const vol = Math.round((a.volume_level || 0) * 100);
    const muted = !!a.is_volume_muted;
    const sleepOn = this._s(c.sleep) === "on";
    const mode = this._s(c.picture_mode);

    const btn = (act, icon, cls = "", dis = false) =>
      `<button class="cb ${cls}" data-act="${act}"${dis ? " disabled" : ""}><ha-icon icon="${icon}"></ha-icon></button>`;

    this.shadowRoot.innerHTML = `
      <style>
        ha-card { overflow: hidden; display: flex; flex-direction: column; }
        button { all: unset; cursor: pointer; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        button[disabled] { opacity: .35; cursor: default; }
        .hero { --fg: var(--${S.c}-color); position: relative; background: ${BMC_TINT(S.c, off ? 10 : 16)};
                padding: 16px; display: flex; gap: 16px; align-items: center; overflow: hidden; }
        .bg { position: absolute; inset: -30px; background-size: cover; background-position: center; filter: blur(28px) saturate(1.3);
              opacity: .35; pointer-events: none; }
        .art { position: relative; flex: none; width: 128px; aspect-ratio: 16 / 9; border-radius: 12px; overflow: hidden;
               display: grid; place-items: center; background: color-mix(in srgb, var(--fg) 18%, var(--card-background-color));
               box-shadow: 0 2px 8px rgba(0,0,0,.18); cursor: pointer; }
        .art img { width: 100%; height: 100%; object-fit: cover; }
        .art.logo img { object-fit: contain; padding: 8px; box-sizing: border-box; background: #fff1; }
        .art ha-icon { --mdc-icon-size: 40px; color: var(--fg); }
        .info { position: relative; flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
        .pill { align-self: flex-start; display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 600;
                background: color-mix(in srgb, var(--fg) 22%, transparent); padding: 3px 10px 3px 8px; border-radius: 12px; }
        .pill ha-icon { --mdc-icon-size: 15px; color: var(--fg); }
        .ttl { font-size: 18px; font-weight: 700; line-height: 1.2; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
        .sub { font-size: 13px; color: var(--secondary-text-color); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .prog { position: relative; padding: 0 16px 14px; background: ${BMC_TINT(S.c, off ? 10 : 16)}; display: flex; flex-direction: column; gap: 6px; }
        .bar { height: 6px; border-radius: 3px; background: color-mix(in srgb, var(--${S.c}-color) 25%, transparent); overflow: hidden; cursor: pointer; }
        .bar i { display: block; height: 100%; border-radius: 3px; background: var(--${S.c}-color); transition: width .9s linear; }
        .times { display: flex; justify-content: space-between; font-size: 12px; color: var(--secondary-text-color); font-variant-numeric: tabular-nums; }
        .times b { color: var(--primary-text-color); font-weight: 600; }
        .body { padding: 14px 16px 16px; display: flex; flex-direction: column; gap: 14px; }
        .ctl { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
        .cb { width: 48px; height: 48px; border-radius: 50%; display: grid; place-items: center; color: var(--primary-text-color);
              background: var(--secondary-background-color, rgba(127,127,127,.12)); transition: transform .1s; }
        .cb:active { transform: scale(.94); }
        .cb ha-icon { --mdc-icon-size: 24px; }
        .cb.main { width: 64px; height: 64px; background: ${BMC_TINT("pink", 22)}; }
        .cb.main ha-icon { --mdc-icon-size: 34px; color: var(--pink-color); }
        .vol { display: flex; align-items: center; gap: 10px; }
        .vol .mute { width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; color: var(--secondary-text-color); }
        .vol .mute.on { color: var(--red-color); background: ${BMC_TINT("red", 14)}; }
        .vol .mute ha-icon { --mdc-icon-size: 22px; }
        .vol input { flex: 1; -webkit-appearance: none; appearance: none; height: 28px; background: transparent; margin: 0; }
        .vol input::-webkit-slider-runnable-track { height: 8px; border-radius: 4px;
          background: linear-gradient(to right, var(--pink-color) var(--v), color-mix(in srgb, var(--pink-color) 20%, transparent) var(--v)); }
        .vol input::-moz-range-track { height: 8px; border-radius: 4px; background: color-mix(in srgb, var(--pink-color) 20%, transparent); }
        .vol input::-moz-range-progress { height: 8px; border-radius: 4px; background: var(--pink-color); }
        .vol input::-webkit-slider-thumb { -webkit-appearance: none; width: 22px; height: 22px; border-radius: 50%; margin-top: -7px;
          background: var(--card-background-color); border: 3px solid var(--pink-color); box-shadow: 0 1px 4px rgba(0,0,0,.3); }
        .vol input::-moz-range-thumb { width: 16px; height: 16px; border-radius: 50%; background: var(--card-background-color); border: 3px solid var(--pink-color); }
        .vol .val { width: 34px; text-align: right; font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; }
        .note { display: flex; align-items: center; gap: 10px; font-size: 14px; padding: 10px 14px; border-radius: 14px; background: ${BMC_TINT("pink", 10)}; }
        .note ha-icon { --mdc-icon-size: 20px; color: var(--pink-color); flex: none; }
        .note span { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .note small { color: var(--secondary-text-color); font-size: 12px; display: block; }
        .h2 { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: .04em; color: var(--secondary-text-color); margin-bottom: -6px; }
        .apps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
        .app { border-radius: 12px; aspect-ratio: 16 / 9; display: grid; place-items: center; overflow: hidden; position: relative;
               background: var(--secondary-background-color, rgba(127,127,127,.12)); transition: transform .1s, box-shadow .2s; }
        .app:active { transform: scale(.96); }
        .app img { width: 100%; height: 100%; object-fit: cover; }
        .app span { display: none; font-size: 12px; font-weight: 600; padding: 4px; text-align: center; }
        .app.noimg img { display: none; }
        .app.noimg span { display: block; }
        .app.on { box-shadow: 0 0 0 3px var(--pink-color); }
        .more { font-size: 13px; font-weight: 500; color: var(--secondary-text-color); display: inline-flex; align-items: center; gap: 4px; align-self: flex-start; }
        .more ha-icon { --mdc-icon-size: 18px; }
        .chips { display: flex; flex-wrap: wrap; gap: 8px; }
        .chip { display: inline-flex; align-items: center; gap: 6px; padding: 8px 12px; border-radius: 18px; font-size: 13px; font-weight: 500;
                color: var(--secondary-text-color); background: var(--secondary-background-color, rgba(127,127,127,.12)); }
        .chip ha-icon { --mdc-icon-size: 17px; }
        .chip.on { color: var(--primary-text-color); background: ${BMC_TINT("pink", 18)}; }
        .chip.on ha-icon { color: var(--pink-color); }
        .modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
        .mode { border-radius: 12px; padding: 10px 4px; text-align: center; font-size: 13px; font-weight: 500; color: var(--secondary-text-color);
                background: var(--secondary-background-color, rgba(127,127,127,.12)); }
        .mode.on { color: var(--primary-text-color); background: ${BMC_TINT("pink", 18)}; font-weight: 600; }
        .big { width: 100%; border-radius: 14px; padding: 14px 16px; display: flex; align-items: center; justify-content: center; gap: 10px;
               font-size: 15px; font-weight: 600; background: ${BMC_TINT("pink", 18)}; transition: transform .1s; }
        .big:active { transform: scale(.98); }
        .big ha-icon { --mdc-icon-size: 22px; color: var(--pink-color); }
      </style>
      <ha-card>
        <div class="hero">
          ${pic && !isLogo ? `<div class="bg" style="background-image:url('${pic}')"></div>` : ""}
          <div class="art${isLogo ? " logo" : ""}" data-act="info">
            ${pic ? `<img src="${pic}" alt="">` : `<ha-icon icon="${S.icon}"></ha-icon>`}
          </div>
          <div class="info">
            <div class="pill"><ha-icon icon="${S.icon}"></ha-icon>${S.label}</div>
            <div class="ttl">${this._esc(title)}</div>
            ${sub ? `<div class="sub">${this._esc(sub)}</div>` : ""}
          </div>
        </div>
        ${p ? `<div class="prog">
          <div class="bar" data-act="seek"><i style="width:${(100 * p.pos) / p.dur}%"></i></div>
          <div class="times"><span class="t-pos">${this._fmt(p.pos)}</span>
            ${end && st === "playing" ? `<span>Endet um <b>${this._hm(end)}</b></span>` : ""}
            <span class="t-rest">−${this._fmt(p.dur - p.pos)}</span></div>
        </div>` : ""}
        <div class="body">
          ${off ? `
            ${has(BMC_F.TURN_ON) ? `<button class="big" data-act="on"><ha-icon icon="mdi:power"></ha-icon>Einschalten</button>` : ""}
          ` : `
            ${media ? `<div class="ctl">
              ${btn("prev", "mdi:skip-previous", "", !has(BMC_F.PREVIOUS))}
              ${btn("back", "mdi:rewind-10", "", !has(BMC_F.SEEK) || !p)}
              ${btn("playpause", st === "playing" ? "mdi:pause" : "mdi:play", "main")}
              ${btn("fwd", "mdi:fast-forward-30", "", !has(BMC_F.SEEK) || !p)}
              ${btn("next", "mdi:skip-next", "", !has(BMC_F.NEXT))}
            </div>` : ""}
            <div class="vol">
              <button class="mute${muted ? " on" : ""}" data-act="mute"><ha-icon icon="${muted ? "mdi:volume-off" : vol < 35 ? "mdi:volume-low" : vol < 70 ? "mdi:volume-medium" : "mdi:volume-high"}"></ha-icon></button>
              <input type="range" min="0" max="100" step="1" value="${vol}" style="--v:${vol}%">
              <span class="val">${vol}</span>
            </div>
            ${this._ok(next) ? `<div class="note"><ha-icon icon="mdi:playlist-play"></ha-icon><span><small>Als Nächstes</small>${this._esc(next)}</span></div>` : ""}
            ${apps.length ? `<div class="h2">Apps</div><div class="apps">${apps.map((x) =>
              `<button class="app${x === curApp ? " on" : ""}" data-act="app" data-app="${this._esc(x)}"><img alt=""><span>${this._esc(x)}</span></button>`).join("")}</div>
              <button class="more" data-act="info"><ha-icon icon="mdi:apps"></ha-icon>Alle Apps & Quellen</button>` : ""}
            <div class="chips">
              ${has(BMC_F.TURN_OFF) ? `<button class="chip" data-act="off"><ha-icon icon="mdi:power"></ha-icon>Ausschalten</button>` : ""}
              ${this._ok(this._s(c.sleep)) && media ? `<button class="chip${sleepOn ? " on" : ""}" data-act="sleep"><ha-icon icon="mdi:power-sleep"></ha-icon>${sleepOn ? "Aus nach diesem Titel" : "Nach Titel aus"}</button>` : ""}
              ${this._ok(mode) ? `<button class="chip${this._modesOpen ? " on" : ""}" data-act="modes"><ha-icon icon="mdi:palette"></ha-icon>Bild: ${BMC_MODES[mode] || mode}</button>` : ""}
            </div>
            ${this._modesOpen && this._ok(mode) ? `<div class="modes">${Object.entries(BMC_MODES).map(([k, l]) =>
              `<button class="mode${k === mode ? " on" : ""}" data-act="mode" data-mode="${k}">${l}</button>`).join("")}</div>` : ""}
          `}
        </div>
      </ha-card>`;

    const r = this.shadowRoot;
    r.querySelectorAll("[data-act]").forEach((el) => el.addEventListener("click", (ev) => this._click(el, ev)));
    r.querySelectorAll(".app").forEach((el) => this._logo(el.dataset.app, el.querySelector("img")));
    const range = r.querySelector(".vol input");
    if (range) {
      const val = r.querySelector(".vol .val");
      range.addEventListener("pointerdown", () => { this._dragging = true; });
      range.addEventListener("pointerup", () => setTimeout(() => { this._dragging = false; }, 300));
      range.addEventListener("input", () => { range.style.setProperty("--v", `${range.value}%`); val.textContent = range.value; });
      range.addEventListener("change", () => {
        this._dragging = false;
        h.callService("media_player", "volume_set", { entity_id: c.entity, volume_level: range.value / 100 });
      });
    }
  }

  _click(el, ev) {
    if (el.disabled) return;
    const c = this._config;
    const h = this._hass;
    const e = { entity_id: c.entity };
    const call = (svc, data = {}) => h.callService("media_player", svc, { ...e, ...data });
    const p = this._position();
    switch (el.dataset.act) {
      case "playpause": call("media_play_pause"); break;
      case "prev": call("media_previous_track"); break;
      case "next": call("media_next_track"); break;
      case "back": if (p) call("media_seek", { seek_position: Math.max(0, p.pos - 10) }); break;
      case "fwd": if (p) call("media_seek", { seek_position: Math.min(p.dur - 1, p.pos + 30) }); break;
      case "seek": {
        if (!p) break;
        const box = el.getBoundingClientRect();
        call("media_seek", { seek_position: Math.round(p.dur * Math.min(1, Math.max(0, (ev.clientX - box.left) / box.width))) });
        break;
      }
      case "mute": call("volume_mute", { is_volume_muted: !h.states[c.entity].attributes.is_volume_muted }); break;
      case "app": call("select_source", { source: el.dataset.app }); break;
      case "on": call("turn_on"); break;
      case "off": call("turn_off"); break;
      case "sleep": h.callService("switch", "toggle", { entity_id: c.sleep }); break;
      case "modes": this._modesOpen = !this._modesOpen; this._sig = null; this._render(); break;
      case "mode": h.callService("select", "select_option", { entity_id: c.picture_mode, option: el.dataset.mode }); break;
      case "info":
        this.dispatchEvent(new CustomEvent("hass-more-info", { bubbles: true, composed: true, detail: { entityId: c.entity } }));
        break;
    }
  }

  static getStubConfig(hass) {
    const e = Object.keys(hass.states).find((k) => k.startsWith("media_player."));
    return { entity: e || "media_player.beamer" };
  }
}

if (!customElements.get("beamer-card")) {
  customElements.define("beamer-card", BeamerCard);
  window.customCards = window.customCards || [];
  window.customCards.push({ type: "beamer-card", name: "Beamer", description: "Media-Bridge-Beamer: Bild/App-Logo, Fortschritt, Steuerung, Lautstärke und Apps" });
}
