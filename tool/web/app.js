//========== WEBVIEW APK BUILDER: DESKTOP WINDOW SCRIPT ==========
// Draws every screen and talks to tool/gui.py through window.pywebview.api.
// Views: setup, apps, edit, build (progress + results), key, settings.

"use strict";

//========== STATE ==========

const S = {
  view: "apps",
  data: null,            // get_state() result
  selected: new Set(),   // folders ticked on the apps screen
  query: "",
  number: null,          // build number field
  edit: null,            // { old, form, icon: {path, dataUrl}, appIdLocked, touched: {folder, applicationId} }
  build: null,           // { number, apps: [...], finished }
  keyTab: "existing",
  setup: null,           // { label, percent, error }
};

const api = (name, ...args) => window.pywebview.api[name](...args);
const $ = (sel) => document.querySelector(sel);

/** Escapes text for HTML. */
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

//========== ICONS ==========
// Stroke icons (24px grid), drawn with currentColor.

const ICONS = {
  box: '<path d="M21 8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4a2 2 0 0 0 1-1.7Z"/><path d="M3.3 7 12 12l8.7-5M12 22V12"/>',
  folder: '<path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.7-.9l-.8-1.2A2 2 0 0 0 7.9 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
  key: '<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 9.3-9.3M17 6l3 3M14.5 8.5l2 2"/>',
  sliders: '<path d="M4 6h10M20 6h0M4 12h4M14 12h6M4 18h12"/><circle cx="17" cy="6" r="2"/><circle cx="11" cy="12" r="2"/><circle cx="19" cy="18" r="2"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  alert: '<path d="M12 3 2 20h20L12 3Z"/><path d="M12 10v4M12 17h.01"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16v4Z"/>',
  trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 15h10l1-15M10 11v6M14 11v6"/>',
  file: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"/>',
  download: '<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="4"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/>',
  lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
  play: '<path d="M7 4v16l13-8z" fill="currentColor"/>',
};
const icon = (name, size = 20) => `<svg class="icon" width="${size}" height="${size}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

// 12-point "cookie" shape for status heroes: r = 46 + 3.5 * cos(12 * t), sampled every 3 degrees.
const COOKIE = (() => {
  const pts = [];
  for (let i = 0; i < 120; i++) {
    const t = (2 * Math.PI * i) / 120;
    const r = 46 + 3.5 * Math.cos(12 * t);
    pts.push(`${(50 + r * Math.sin(t)).toFixed(1)} ${(50 - r * Math.cos(t)).toFixed(1)}`);
  }
  return `M${pts.join(" L")}Z`;
})();
const cookie = (fill, size, inner) =>
  `<div style="position:relative;width:${size}px;height:${size}px;flex-shrink:0"><svg width="${size}" height="${size}" viewBox="0 0 100 100" aria-hidden="true"><path fill="${fill}" d="${COOKIE}"/></svg><div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center">${inner}</div></div>`;

// Expressive avatar shapes and tones, cycled per app.
const SHAPES = ["50%", "14px", "50% 50% 50% 12px", "22px 8px 22px 8px"];

//========== HELPERS ==========

function snackbar(text) {
  const el = $("#snackbar");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(snackbar.timer);
  snackbar.timer = setTimeout(() => el.classList.remove("show"), 4000);
}

function applyTheme() {
  const theme = S.data?.settings?.theme || "system";
  if (theme === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", theme);
}

function initials(name) {
  return (name || "?").split(/\s+/).filter(Boolean).map((w) => (/^\d+$/.test(w) ? w : w[0])).join("").slice(0, 3).toUpperCase();
}

function avatar(app, index, selected) {
  const shape = selected ? "50%" : SHAPES[index % SHAPES.length];
  const inner = app.icon ? `<img src="${app.icon}" alt="">` : esc(initials(app.appName));
  return `<span class="avatar" style="border-radius:${shape};background:${esc(app.iconBackground)}">${inner}</span>`;
}

/** Folder name from an app name: lower case, each run of other characters becomes one "-". */
const slug = (name) => name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
const suggestId = (name) => {
  let id = slug(name).replace(/-/g, "") || "app";
  if (/^\d/.test(id)) id = "app" + id;
  return "com.example." + id;
};
const RE = {
  url: /^https?:\/\/\S+$/i,
  appId: /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/,
  version: /^\d+(\.\d+){0,3}$/,
  color: /^#[0-9a-fA-F]{6}$/,
  folder: /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/,
};

function field({ id, label, value, help = "", mono = false, type = "text", locked = false, extra = "" }) {
  return `<div class="field${locked ? " locked" : ""}" id="f-${id}">
    <div class="box">
      <label class="float" for="${id}">${esc(label)}</label>
      <input id="${id}" data-bind="${id}" type="${type}" value="${esc(value)}" ${locked ? "disabled" : ""} class="${mono ? "mono" : ""}" autocomplete="off" spellcheck="false">
      ${extra}
    </div>
    <span class="help" id="h-${id}">${esc(help)}</span>
  </div>`;
}

function wavy(percent) {
  // A sine path, 20px per half wave; the visible part grows with the percentage.
  const d = "M0 8 q10 -7 20 0" + " t20 0".repeat(60);
  return `<div class="wavy" role="progressbar" aria-valuenow="${Math.round(percent)}" aria-valuemin="0" aria-valuemax="100">
    <div class="done" style="width:${Math.max(2, percent)}%"><svg width="1240" height="16" viewBox="0 0 1240 16" aria-hidden="true"><path d="${d}" fill="none" stroke="var(--primary)" stroke-width="4" stroke-linecap="round"/></svg></div>
    <div class="rest"></div><span class="stop"></span></div>`;
}

//========== SHELL ==========

function shell(active, content, full = false) {
  const item = (view, ic, label) =>
    `<button class="rail-item${active === view ? " active" : ""}" data-act="nav" data-view="${view}"><span class="pill">${icon(ic, 22)}</span>${label}</button>`;
  return `<nav class="rail" aria-label="Sections">
      ${item("apps", "box", "Build")}
      <button class="rail-item" data-act="open-dist"><span class="pill">${icon("folder", 22)}</span>Output</button>
      ${item("key", "key", "Signing")}
      <div class="spacer"></div>
      ${item("settings", "sliders", "Settings")}
    </nav>
    <main class="content${full ? " full" : ""}">${content}</main>`;
}

function render() {
  applyTheme();
  const app = $("#app");
  const d = S.data;
  if (!d) return;
  if (S.build && !S.build.finished && S.view !== "build") S.view = "build";
  const views = { setup: viewSetup, apps: viewApps, edit: viewEdit, build: viewBuild, key: viewKey, settings: viewSettings };
  app.innerHTML = views[S.view]();
  if (S.view === "edit") updateEdit();
}

async function reload() {
  S.data = await api("get_state");
  if (S.number == null) S.number = S.data.nextBuild;
  if (!S.data.env.ok && S.view === "apps") S.view = "setup";
  render();
}

//========== SETUP ==========

function viewSetup() {
  const env = S.data.env;
  const row = (ok, title, detail, first, last) => `<div class="row" style="${first ? "border-radius:24px 24px 6px 6px" : last ? "border-radius:6px 6px 24px 24px" : ""}">
      <span class="dot ${ok ? "ok" : "bad"}">${icon(ok ? "check" : "x")}</span>
      <span style="flex-grow:1;display:flex;flex-direction:column"><b>${title}</b><span class="muted" style="font-size:12px">${esc(detail)}</span></span></div>`;
  const s = S.setup;
  const busy = s && !s.error && !s.done;
  return shell("apps", `<div style="max-width:640px;margin:auto;display:flex;flex-direction:column;gap:22px;width:100%">
      <div style="display:flex;align-items:center;gap:20px">
        <span class="dot" style="width:80px;height:80px;border-radius:28px 28px 28px 8px;background:var(--warning-container);color:var(--on-warning-container)">${icon("alert", 38)}</span>
        <div><h1 style="font-size:36px">${env.ok ? "All set" : "A few tools are missing"}</h1>
        <p class="subtitle">Building needs Java 17+ and the Android SDK. The builder can install both for you (about 1 GB, once).</p></div>
      </div>
      <div class="list">
        ${row(true, "Python", "Found: " + env.python, true, false)}
        ${row(!!env.java, "Java 17 or newer", env.java || "Not found in tools, JAVA_HOME, PATH or Android Studio", false, false)}
        ${row(!!env.sdk, "Android SDK", env.sdk || "Not found in tools, local.properties, ANDROID_HOME or Android Studio", false, true)}
      </div>
      ${s ? `<div style="display:flex;flex-direction:column;gap:8px">
          <span>${esc(s.error ? "Setup failed: " + s.error : s.label || "Starting…")}</span>
          ${s.error ? "" : `<div class="bar"><div style="width:${s.percent || 0}%"></div></div>`}</div>` : ""}
      <div style="display:flex;justify-content:flex-end;gap:8px">
        <button class="btn btn-text" data-act="check-env" ${busy ? "disabled" : ""}>Check again</button>
        ${env.ok ? `<button class="btn btn-lg btn-filled" data-act="nav" data-view="apps">Continue</button>`
          : `<button class="btn btn-lg btn-filled" data-act="install-tools" ${busy ? "disabled" : ""}>${icon("download")}Install for me</button>`}
      </div>
    </div>`, true);
}

//========== APPS ==========

function viewApps() {
  const d = S.data;
  const q = S.query.toLowerCase();
  const apps = d.apps.filter((a) => !q || a.appName.toLowerCase().includes(q) || a.folder.toLowerCase().includes(q));
  const n = S.selected.size;
  const key = d.key.status === "release"
    ? `<span class="chip ok">${icon("check", 18)}Release key</span>`
    : `<button class="chip warn" data-act="nav" data-view="key">${icon("key", 18)}Debug key</button>`;
  const cards = apps.map((a, i) => {
    const on = S.selected.has(a.folder);
    return `<button class="app-card${on ? " selected" : ""}" data-act="toggle" data-folder="${esc(a.folder)}" aria-pressed="${on}">
        ${avatar(a, i, on)}
        <span class="text"><span class="name">${esc(a.appName)}</span><span class="sub">${esc(a.folder)} · v${esc(a.versionName)}</span></span>
        <span class="btn-icon" role="button" title="Edit" data-act="edit" data-folder="${esc(a.folder)}">${icon("edit", 18)}</span>
        <span class="box-check">${on ? icon("check", 16) : ""}</span>
      </button>`;
  }).join("");
  return shell("apps", `
    <div style="display:flex;align-items:flex-end;gap:24px;flex-wrap:wrap">
      <div style="flex-grow:1"><h1>Build APKs</h1><p class="subtitle">Choose the apps to build. Each one becomes a release APK in the output folder.</p></div>
      <div class="chips">
        <span class="chip ok">${icon("check", 18)}${esc((d.env.java || "").split(" (")[0])}</span>
        <span class="chip ok">${icon("check", 18)}Android SDK</span>
        ${key}
      </div>
    </div>
    <div class="toolbar">
      <label class="search">${icon("search")}<input id="search" data-bind="search" placeholder="Search ${d.apps.length} apps" value="${esc(S.query)}" aria-label="Search apps"></label>
      <div class="group"><button class="btn btn-tonal" data-act="select-all">Select all</button><button class="btn btn-tonal" data-act="clear">Clear</button></div>
      <div style="flex-grow:1"></div>
      <span class="muted"><b style="color:var(--on-surface)">${n}</b> of ${d.apps.length} selected</span>
    </div>
    <div class="grid">${cards}
      <button class="add-card" data-act="add"><span class="avatar">${icon("plus", 24)}</span><span><b style="font-size:15px">Add app</b><br><span class="muted" style="font-size:12px">New folder in apps</span></span></button>
    </div>
    <div class="bottom-bar">
      <div class="field" style="width:200px">
        <div class="box"><label class="float" for="number">Build number</label><input id="number" data-bind="number" value="${esc(S.number)}" inputmode="numeric"></div>
        <span class="help">Last local build: ${d.lastBuild || "none"}</span>
      </div>
      <div class="grow"></div>
      <button class="btn btn-fab btn-filled" data-act="plan" ${n ? "" : "disabled"}>${icon("play", 24)}Build ${n === 1 ? "1 app" : n + " apps"}</button>
    </div>`);
}

//========== EDIT ==========

function startEdit(folder) {
  const a = folder ? S.data.apps.find((x) => x.folder === folder) : null;
  S.edit = {
    old: folder || null,
    form: a ? { ...a } : { appName: "", appUrl: "https://", folder: "", applicationId: "", versionName: "1.0.0", iconBackground: "#FFFFFF", pullToRefresh: true },
    original: a,
    icon: null,
    appIdLocked: !!a,
    touched: { folder: !!a, applicationId: !!a },
  };
  S.view = "edit";
  render();
}

function viewEdit() {
  const e = S.edit, f = e.form;
  const swatches = ["#FFFFFF", "#E0E0FF", "#B3F1C6", "#FFDDB4", "#FFD8E8", "#1B1B21"]
    .map((c) => `<button class="swatch${c.toLowerCase() === f.iconBackground.toLowerCase() ? " on" : ""}" style="background:${c}" data-act="swatch" data-color="${c}" aria-label="${c}"></button>`).join("");
  const bumps = e.original ? `<div class="group" style="margin-top:-4px">${[2, 1, 0].map((p) => {
    const v = bump(e.original.versionName, p);
    return `<button class="btn btn-tonal" style="height:30px;font-size:12px" data-act="bump" data-version="${v}">${v}</button>`;
  }).join("")}</div>` : "";
  return shell("apps", `
    <div class="topbar">
      <button class="btn-icon" data-act="nav" data-view="apps" aria-label="Close without saving">${icon("x", 22)}</button>
      <h2 style="flex-grow:1">${e.original ? "Edit " + esc(e.original.appName) : "Add app"}</h2>
      <button class="btn btn-lg btn-filled" data-act="save" id="save-btn">${icon("check")}Save</button>
    </div>
    <div class="form-cols">
      <div class="form">
        ${field({ id: "appName", label: "App name", value: f.appName, help: "Shown below the icon on the phone." })}
        ${field({ id: "appUrl", label: "Website URL", value: f.appUrl, mono: true, help: "The page the app opens." })}
        <div class="two">
          ${field({ id: "folder", label: "Folder", value: f.folder, mono: true, help: "Filled in from the app name." })}
          <div>${field({ id: "versionName", label: "Version", value: f.versionName, help: "e.g. 1.0.0" })}${bumps}</div>
        </div>
        ${field({ id: "applicationId", label: "Application ID", value: f.applicationId, mono: true, locked: e.appIdLocked,
          extra: e.appIdLocked ? `<button class="btn btn-text" data-act="unlock-id">Unlock</button>` : "" })}
        <div style="display:flex;align-items:center;gap:14px;flex-wrap:wrap">
          <span class="muted" style="width:120px">Icon background</span>
          <div class="swatches">${swatches}</div>
          <input data-bind="iconBackground" id="iconBackground" class="mono" value="${esc(f.iconBackground)}" style="width:90px;border:1px solid var(--outline);border-radius:8px;padding:6px 8px;background:transparent">
        </div>
        <label class="check"><input type="checkbox" data-bind="pullToRefresh" ${f.pullToRefresh ? "checked" : ""}>Pull to refresh (swipe down at the top of the page to reload)</label>
        ${e.original ? `<div style="margin-top:auto"><button class="btn btn-danger-outlined" data-act="ask-delete">${icon("trash", 18)}Delete app…</button></div>` : ""}
        <div id="edit-error"></div>
      </div>
      <aside class="side">
        <div class="preview">
          <span class="avatar" id="preview-icon"></span>
          <b id="preview-name" style="font-size:13px;color:var(--on-nav-indicator)"></b>
          <button class="btn btn-tonal" style="width:100%" data-act="pick-icon">${icon("image", 18)}${e.original ? "Replace icon.png" : "Choose icon"}</button>
          <span style="font-size:12px;color:var(--on-nav-indicator);text-align:center">Square PNG, 1024 × 1024 recommended.</span>
        </div>
        <div class="code mono" id="preview-json"></div>
      </aside>
    </div>`);
}

function bump(version, part) {
  const p = version.split(".").map((x) => parseInt(x, 10) || 0);
  while (p.length < 3) p.push(0);
  p[part] += 1;
  for (let i = part + 1; i < p.length; i++) p[i] = 0;
  return p.join(".");
}

/** Validation and preview, updated on every keystroke without redrawing the inputs. */
function updateEdit() {
  const e = S.edit, f = e.form;
  if (!e.touched.folder) f.folder = slug(f.appName);
  if (!e.touched.applicationId) f.applicationId = suggestId(f.appName);
  for (const id of ["folder", "applicationId"]) {
    const input = document.getElementById(id);
    if (input && document.activeElement !== input) input.value = f[id];
  }
  const others = S.data.apps.filter((a) => a.folder !== e.old);
  const owner = others.find((a) => a.applicationId === f.applicationId);
  const errors = {
    appName: f.appName.trim() ? null : "Enter a name",
    appUrl: RE.url.test(f.appUrl) ? null : "Start the URL with http:// or https://",
    folder: !RE.folder.test(f.folder) ? "Letters, digits, -, _ and . only" : others.some((a) => a.folder === f.folder) ? "Another app uses this folder" : null,
    versionName: RE.version.test(f.versionName) ? null : "Use numbers like 1.0.0",
    applicationId: !RE.appId.test(f.applicationId) ? "Use the form com.company.app"
      : owner ? `Already used by ${owner.appName}. Two apps with one ID replace each other on the phone.` : null,
  };
  const helps = {
    appName: "Shown below the icon on the phone.", appUrl: "The page the app opens.", folder: "Filled in from the app name.",
    versionName: "e.g. 1.0.0",
    applicationId: e.appIdLocked ? "Locked: changing it makes phones treat this as a different app." : "Unique. Do not change it after the app is released.",
  };
  for (const [id, err] of Object.entries(errors)) {
    document.getElementById("f-" + id)?.classList.toggle("error", !!err && !(id === "appName" && !f.appName));
    const help = document.getElementById("h-" + id);
    if (help) help.textContent = err && !(id === "appName" && !f.appName) ? err : helps[id];
  }
  const iconUrl = e.icon?.dataUrl || e.original?.icon;
  const prev = $("#preview-icon");
  if (prev) {
    prev.style.background = RE.color.test(f.iconBackground) ? f.iconBackground : "#FFFFFF";
    prev.innerHTML = iconUrl ? `<img src="${iconUrl}" alt="">` : esc(initials(f.appName));
  }
  if ($("#preview-name")) $("#preview-name").textContent = f.appName || "App name";
  const json = { appName: f.appName, appUrl: f.appUrl, applicationId: f.applicationId, versionName: f.versionName, iconBackground: f.iconBackground, pullToRefresh: f.pullToRefresh };
  if ($("#preview-json")) $("#preview-json").textContent = `apps/${f.folder}/config.json\n` + JSON.stringify(json, null, 2);
  const valid = Object.values(errors).every((x) => !x) && RE.color.test(f.iconBackground) && (e.old || e.icon);
  if ($("#save-btn")) $("#save-btn").disabled = !valid;
}

//========== BUILD ==========

async function showPlan() {
  const folders = [...S.selected];
  const number = parseInt(S.number, 10);
  if (!(number > 0)) return snackbar("The build number must be a whole number, starting at 1.");
  const plan = await api("plan", folders, number);
  const chips = folders.map((f) => {
    const a = S.data.apps.find((x) => x.folder === f);
    return `<span class="chip" style="background:var(--nav-indicator);color:var(--on-nav-indicator);border:0;border-radius:16px">${esc(a?.appName || f)}</span>`;
  }).join("");
  const warns = plan.warnings.map((w) => `<div class="banner warn">${icon("alert")}<span>${esc(w)}</span></div>`).join("");
  dialog(`
    <div style="display:flex;align-items:center;gap:16px">${cookie("var(--primary-container)", 52, `<span style="color:var(--on-primary-container)">${icon("play", 22)}</span>`)}
      <div><h2>Start the build?</h2><p class="subtitle" style="margin:0">${folders.length} app${folders.length > 1 ? "s" : ""}, about a minute each.</p></div></div>
    <div class="chips">${chips}</div>
    <dl class="facts">
      <dt>Build number</dt><dd><b>${number}</b></dd>
      <dt>Output</dt><dd class="mono">${esc(plan.output)}</dd>
      <dt>Signing</dt><dd>${esc(plan.signing)}</dd>
      <dt>Java</dt><dd>${esc(plan.java)}</dd>
      <dt>SDK</dt><dd class="mono">${esc(plan.sdk)}</dd>
    </dl>
    ${warns}
    <div class="actions"><button class="btn btn-text" data-act="close-dialog">Cancel</button>
      <button class="btn btn-filled" data-act="start-build">${icon("play", 18)}Start build</button></div>`);
}

function startBuild() {
  closeDialog();
  const folders = [...S.selected];
  const number = parseInt(S.number, 10);
  S.build = {
    number,
    started: Date.now(),
    apps: folders.map((f) => ({ folder: f, name: S.data.apps.find((a) => a.folder === f)?.appName || f, status: "waiting", steps: [], line: "" })),
    finished: null,
  };
  S.view = "build";
  render();
  api("start_build", folders, number);
}

const STEP_NAMES = ["Generating launcher icons", "Compiling Kotlin", "Shrinking with R8", "Packaging APK"];

function viewBuild() {
  const b = S.build;
  if (!b) return viewApps();
  if (b.finished) return viewResults();
  const done = b.apps.filter((a) => !["waiting", "running"].includes(a.status)).length;
  const cur = b.apps.find((a) => a.status === "running");
  // Overall: finished apps plus the share of Gradle steps seen in the current one.
  const share = cur ? cur.steps.length / (STEP_NAMES.length + 1) : 0;
  const percent = ((done + share) / b.apps.length) * 100;
  const steps = cur ? STEP_NAMES.map((s, i) => {
    const seen = cur.steps.includes(s);
    const now = seen && i === cur.steps.length - 1;
    const mark = seen && !now ? `<span class="dot ok" style="width:24px;height:24px">${icon("check", 14)}</span>`
      : now ? `<svg class="spin" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="var(--primary-container)" stroke-width="3"/><path d="M12 3a9 9 0 0 1 9 9" fill="none" stroke="var(--primary)" stroke-width="3" stroke-linecap="round"/></svg>`
      : `<span style="width:20px;height:20px;margin:0 2px;border-radius:50%;border:2px solid var(--outline-variant)"></span>`;
    return `<li class="${now ? "now" : seen ? "" : "later"}">${mark}${s}</li>`;
  }).join("") : "";
  const queue = b.apps.map((a) => {
    const dot = a.status === "ok" ? `<span class="dot ok" style="width:36px;height:36px">${icon("check", 18)}</span>`
      : a.status === "running" ? `<span class="dot" style="width:36px;height:36px;border-radius:12px;background:var(--primary);color:var(--on-primary);font-weight:800;font-size:12px">${esc(initials(a.name))}</span>`
      : ["failed", "cancelled"].includes(a.status) ? `<span class="dot bad" style="width:36px;height:36px">${icon("x", 18)}</span>`
      : `<span class="dot wait" style="width:36px;height:36px;font-size:12px;font-weight:800">${esc(initials(a.name))}</span>`;
    const detail = { ok: `Built in ${a.seconds?.toFixed(0)}s · ${a.size}`, running: "Building…", failed: "Failed", cancelled: "Cancelled", waiting: "Waiting" }[a.status];
    return `<div class="row" style="${a.status === "running" ? "background:var(--primary-container);color:var(--on-primary-container)" : ""}">${dot}
      <span style="display:flex;flex-direction:column"><b>${esc(a.name)}</b><span style="font-size:12px">${detail}</span></span></div>`;
  }).join("");
  return shell("apps", `
    <div style="display:flex;align-items:center;gap:16px">
      <div style="flex-grow:1"><h1>Building ${Math.min(done + 1, b.apps.length)} of ${b.apps.length}</h1>
      <p class="subtitle">Build ${b.number} · elapsed <span class="mono" id="elapsed">${elapsed(b.started)}</span></p></div>
      <button class="btn btn-outlined" data-act="cancel-build" style="height:48px">${icon("x", 18)}Cancel</button>
    </div>
    ${wavy(percent)}
    <div style="display:flex;gap:16px">
      <section class="card" style="flex-grow:1;display:flex;gap:24px;min-width:0">
        ${cur ? `<div class="spin-slow">${cookie("var(--primary-container)", 112, "")}</div>
        <div style="flex-grow:1;display:flex;flex-direction:column;gap:14px;min-width:0">
          <div><h2>${esc(cur.name)}</h2><p class="muted" style="margin:2px 0 0;font-size:13px">${esc(cur.folder)}</p></div>
          <ol class="steps">${steps}</ol></div>` : `<span class="muted">Finishing…</span>`}
      </section>
      <section style="width:300px;flex-shrink:0;display:flex;flex-direction:column;gap:6px">
        <span class="label" style="margin:0 0 4px 4px">Queue</span><div class="list">${queue}</div>
      </section>
    </div>
    <div class="log mono" id="log-line">${esc(cur?.line || " ")}</div>`, true);
}

function elapsed(start) {
  const s = Math.floor((Date.now() - start) / 1000);
  return `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function viewResults() {
  const b = S.build, fin = b.finished;
  const ok = b.apps.filter((a) => a.status === "ok");
  const title = fin.cancelled ? "Build cancelled" : ok.length === b.apps.length ? (ok.length === 1 ? "Your APK is ready" : `All ${ok.length} APKs are ready`) : `${ok.length} of ${b.apps.length} APKs ready`;
  const rows = b.apps.map((a) => {
    if (a.status === "ok") {
      return `<div class="row result">${`<span class="dot ok">${icon("check")}</span>`}
        <span style="width:170px;display:flex;flex-direction:column"><b>${esc(a.name)}</b><span class="muted" style="font-size:12px">${a.seconds.toFixed(0)}s</span></span>
        <span class="mono" style="flex-grow:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px">${esc(a.name_apk)}</span>
        <span class="muted" style="width:60px;text-align:right">${esc(a.size)}</span>
        <button class="btn-icon" data-act="reveal" data-path="${esc(a.apk)}" title="Show in folder">${icon("folder")}</button></div>`;
    }
    const reason = (a.reason || []).join("\n") + ((a.hints || []).length ? "\n\n" + a.hints.join("\n") : "");
    return `<div class="result ${a.status === "failed" ? "failed" : ""}">
      <div class="row" style="background:transparent"><span class="dot bad">${icon("x")}</span>
        <span style="width:170px;display:flex;flex-direction:column"><b>${esc(a.name)}</b><span style="font-size:12px">${a.status === "failed" ? "Failed" : "Cancelled"}</span></span>
        <span style="flex-grow:1"></span>
        ${a.status === "failed" ? `<button class="btn btn-danger" data-act="retry" data-folder="${esc(a.folder)}">${icon("refresh", 16)}Retry</button>` : ""}</div>
      ${reason ? `<pre class="mono">${esc(reason)}</pre>` : ""}</div>`;
  }).join("");
  const heroFill = ok.length ? "var(--success-container)" : "var(--error-container)";
  const heroIcon = ok.length ? `<span style="color:var(--on-success-container)">${icon("check", 44)}</span>` : `<span style="color:var(--on-error-container)">${icon("x", 44)}</span>`;
  return shell("apps", `
    <div style="display:flex;align-items:center;gap:22px">${cookie(heroFill, 96, heroIcon)}
      <div><h1 style="font-size:44px;line-height:50px">${title}</h1>
      <p class="subtitle">Build ${b.number} · ${Math.round(fin.seconds)}s</p></div></div>
    <div class="list">${rows}</div>
    <div style="margin-top:auto;display:flex;align-items:center;gap:10px">
      <span class="muted mono" style="flex-grow:1;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(fin.log)}</span>
      <button class="btn btn-text" data-act="build-more">Build more</button>
      <button class="btn btn-tonal" data-act="reveal" data-path="${esc(fin.log)}">${icon("file", 18)}Open log</button>
      <button class="btn btn-lg btn-filled" data-act="open-dist">${icon("folder")}Open output folder</button>
    </div>`, true);
}

//========== SIGNING KEY ==========

function viewKey() {
  const k = S.data.key;
  const status = k.status === "release"
    ? `<div class="banner ${k.problem ? "bad" : "ok"}">${icon(k.problem ? "alert" : "check")}<span><b>Release key: ${esc(k.alias)}</b><br>
        ${k.problem ? esc(k.problem) : `Valid until ${esc(k.validUntil || "?")}`}<br><span class="mono" style="font-size:12px">${esc(k.path)}</span>
        ${k.fingerprint ? `<br><span class="mono" style="font-size:11px">SHA-256 ${esc(k.fingerprint)}</span>` : ""}</span></div>
       <div style="display:flex;gap:8px"><button class="btn btn-tonal" data-act="show-secrets">${icon("copy", 18)}Copy GitHub secrets</button>
        <button class="btn btn-text" style="color:var(--error)" data-act="remove-key">Stop using this key</button></div>`
    : `<div class="banner warn">${icon("alert")}<span>No release key yet. APKs are signed with this computer's debug key, so they can't update apps installed from another computer or from GitHub.</span></div>`;
  const existing = `
    <div class="form" style="max-width:640px">
      <div class="field"><div class="box"><label class="float" for="k-path">Keystore file (.jks, .keystore, .p12)</label>
        <input id="k-path" class="mono" placeholder="Choose a file"><button class="btn btn-tonal" style="height:32px" data-act="pick-keystore">Browse…</button></div></div>
      <div class="two">
        <div class="field"><div class="box"><label class="float" for="k-store">Keystore password</label><input id="k-store" type="password"></div></div>
        <div class="field"><div class="box"><label class="float" for="k-keypass">Key password (if different)</label><input id="k-keypass" type="password"></div></div>
      </div>
      <div class="field"><div class="box"><label class="float" for="k-alias-in">Key alias (empty: the first key)</label><input id="k-alias-in" class="mono"></div></div>
      <div><button class="btn btn-lg btn-filled" data-act="key-import">${icon("check")}Use this key</button></div>
    </div>`;
  const create = `
    <div class="form-cols">
      <div class="form">
        <div class="field"><div class="box"><label class="float" for="c-alias">Key alias</label><input id="c-alias" class="mono" value="upload"></div></div>
        <div class="field"><div class="box"><label class="float" for="c-pass">Password (at least 6 characters)</label><input id="c-pass" type="password">
          <button class="btn btn-tonal" style="height:32px" data-act="gen-pass">Generate</button></div><span class="help" id="c-pass-help">Write it down: you need it to restore a backup.</span></div>
        <div class="field"><div class="box"><label class="float" for="c-pass2">Confirm password</label><input id="c-pass2" type="password"></div></div>
        <div class="field"><div class="box"><label class="float" for="c-years">Valid for</label>
          <select id="c-years">${[25, 27, 30, 40, 50].map((y) => `<option value="${y}" ${y === 27 ? "selected" : ""}>${y} years</option>`).join("")}</select></div></div>
      </div>
      <div class="form">
        <div class="field"><div class="box"><label class="float" for="c-name">Your name or company</label><input id="c-name"></div></div>
        <div class="two"><div class="field"><div class="box"><label class="float" for="c-org">Organization (optional)</label><input id="c-org"></div></div>
          <div class="field" style="flex:0 0 100px"><div class="box"><label class="float" for="c-country">Country</label><input id="c-country" maxlength="2" placeholder="US"></div></div></div>
        <div class="field"><div class="box"><label class="float" for="c-path">Save keystore to</label><input id="c-path" class="mono">
          <button class="btn btn-tonal" style="height:32px" data-act="pick-save-keystore">Browse…</button></div></div>
      </div>
    </div>
    <div style="display:flex;align-items:center;gap:14px">
      <div class="banner warn" style="flex-grow:1">${icon("alert")}<span>Back up the keystore file and its password. If either is lost, phones must uninstall the apps before the next update.</span></div>
      <button class="btn btn-lg btn-filled" data-act="key-create">${icon("key")}Create key</button>
    </div>`;
  return shell("key", `
    <div><h1>Signing key</h1><p class="subtitle">One release key for every app, so phones accept each new APK as an update.</p></div>
    ${status}
    <div class="group"><button class="btn btn-tonal ${S.keyTab === "existing" ? "on" : ""}" data-act="key-tab" data-tab="existing">Use an existing keystore</button>
      <button class="btn btn-tonal ${S.keyTab === "create" ? "on" : ""}" data-act="key-tab" data-tab="create">Create a new key</button></div>
    ${S.keyTab === "existing" ? existing : create}`);
}

async function showSecrets() {
  const s = await api("key_secrets");
  if (!s) return;
  S.secrets = s;
  const rows = Object.entries(s).map(([k, v]) => `<div class="secret"><b style="width:160px;font-size:12px">${k}</b>
    <span class="mono">${k.includes("PASSWORD") ? "••••••••" : esc(v.slice(0, 60))}</span>
    <button class="btn btn-tonal" style="height:30px" data-act="copy-secret" data-name="${k}">${icon("copy", 16)}Copy</button></div>`).join("");
  dialog(`<h2>GitHub secrets</h2>
    <p class="muted" style="margin:0">Add these in your repository under Settings, Secrets and variables, Actions, so builds on GitHub use the same key.</p>
    ${rows}<div class="actions"><button class="btn btn-filled" data-act="close-dialog">Done</button></div>`);
}

//========== SETTINGS ==========

function viewSettings() {
  const theme = S.data.settings.theme || "system";
  const opt = (v, label) => `<button class="btn btn-tonal ${theme === v ? "on" : ""}" data-act="theme" data-theme="${v}">${label}</button>`;
  return shell("settings", `
    <div><h1>Settings</h1></div>
    <span class="label">Theme</span>
    <div class="group">${opt("system", "System")}${opt("light", "Light")}${opt("dark", "Dark")}</div>
    <span class="label" style="margin-top:12px">Workspace</span>
    <div class="row" style="border-radius:24px"><span style="flex-grow:1;display:flex;flex-direction:column">
      <b>Project folder</b><span class="mono muted" style="font-size:12px">${esc(S.data.workspace)}</span></span>
      <button class="btn btn-tonal" data-act="reveal" data-path="${esc(S.data.workspace)}">${icon("folder", 18)}Open</button></div>
    <span class="label" style="margin-top:12px">Tools</span>
    <div class="row" style="border-radius:24px"><span style="flex-grow:1;display:flex;flex-direction:column">
      <b>${esc(S.data.env.java || "Java not found")}</b><span class="mono muted" style="font-size:12px">${esc(S.data.env.sdk || "Android SDK not found")}</span></span>
      <button class="btn btn-tonal" data-act="nav" data-view="setup">Setup</button></div>
    <p class="muted" style="margin-top:auto">WebView APK Builder ${esc(S.data.version)}</p>`);
}

//========== DIALOGS ==========

function dialog(html) {
  $("#dialog-root").innerHTML = `<div class="scrim" data-act="scrim"><div class="dialog" role="dialog">${html}</div></div>`;
}
function closeDialog() { $("#dialog-root").innerHTML = ""; }

function askDelete() {
  const e = S.edit;
  dialog(`
    <span class="dot" style="width:56px;height:56px;border-radius:20px 20px 20px 6px;background:var(--error-container);color:var(--on-error-container)">${icon("trash", 28)}</span>
    <div><h2>Delete ${esc(e.original.appName)}?</h2>
    <p class="muted" style="margin:6px 0 0">The folder goes to the Recycle Bin, so you can restore it. Phones that already have the app keep it.</p></div>
    <label class="check"><input type="checkbox" id="del-apks">Also delete its APKs in the output folder</label>
    <div class="field"><div class="box"><label class="float" for="del-confirm">Type ${esc(e.old)} to confirm</label><input id="del-confirm" class="mono" data-bind="del-confirm"></div></div>
    <div class="actions"><button class="btn btn-text" data-act="close-dialog">Cancel</button>
      <button class="btn btn-danger" id="del-btn" data-act="delete" disabled>${icon("trash", 18)}Delete app</button></div>`);
}

//========== ACTIONS ==========

const actions = {
  nav: (el) => { S.view = el.dataset.view; if (S.view === "apps") S.edit = null; render(); },
  "open-dist": () => api("open_dist"),
  reveal: (el) => api("open_path", el.dataset.path),
  toggle: (el, ev) => {
    if (ev.target.closest('[data-act="edit"]')) return;
    const f = el.dataset.folder;
    S.selected.has(f) ? S.selected.delete(f) : S.selected.add(f);
    render();
  },
  edit: (el, ev) => { ev.stopPropagation(); startEdit(el.dataset.folder); },
  add: () => startEdit(null),
  "select-all": () => { S.data.apps.forEach((a) => S.selected.add(a.folder)); render(); },
  clear: () => { S.selected.clear(); render(); },
  plan: () => showPlan(),
  "start-build": () => startBuild(),
  "cancel-build": () => api("cancel_build"),
  "build-more": () => { S.build = null; S.view = "apps"; reload(); },
  retry: (el) => { S.selected = new Set([el.dataset.folder]); S.build = null; S.view = "apps"; showPlan(); },
  "close-dialog": () => closeDialog(),
  scrim: (el, ev) => { if (ev.target === el) closeDialog(); },
  "check-env": async () => { S.setup = null; await reload(); },
  "install-tools": () => { S.setup = { label: "Starting…", percent: 0 }; render(); api("install_tools"); },
  swatch: (el) => { S.edit.form.iconBackground = el.dataset.color; render(); },
  bump: (el) => { S.edit.form.versionName = el.dataset.version; render(); },
  "unlock-id": () => { S.edit.appIdLocked = false; render(); },
  "pick-icon": async () => { const r = await api("pick_icon"); if (r) { S.edit.icon = r; updateEdit(); } },
  save: async () => {
    const e = S.edit;
    const err = await api("save_app", e.form, e.old, e.icon?.path || null);
    if (err) { $("#edit-error").innerHTML = `<div class="banner bad">${icon("alert")}<span>${esc(err)}</span></div>`; return; }
    snackbar(`${e.form.appName} saved`);
    S.view = "apps"; S.edit = null; reload();
  },
  "ask-delete": () => askDelete(),
  delete: async () => {
    const e = S.edit;
    await api("delete_app", e.old, $("#del-apks").checked);
    closeDialog();
    S.selected.delete(e.old);
    snackbar(`${e.original.appName} moved to the Recycle Bin`);
    S.view = "apps"; S.edit = null; reload();
  },
  "key-tab": (el) => { S.keyTab = el.dataset.tab; render(); },
  "pick-keystore": async () => { const p = await api("pick_keystore"); if (p) $("#k-path").value = p; },
  "pick-save-keystore": async () => { const p = await api("pick_save_keystore"); if (p) $("#c-path").value = p; },
  "gen-pass": () => {
    // 20 characters from a 56-symbol set (no look-alikes): about 116 bits of randomness.
    const chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    const bytes = crypto.getRandomValues(new Uint32Array(20));
    const pass = Array.from(bytes, (b) => chars[b % chars.length]).join("");
    $("#c-pass").value = pass; $("#c-pass2").value = pass;
    $("#c-pass").type = "text";
  },
  "key-import": async () => {
    const path = $("#k-path").value.trim();
    if (!path) return snackbar("Choose a keystore file first.");
    const err = await api("key_import", { path, storePassword: $("#k-store").value, keyPassword: $("#k-keypass").value, alias: $("#k-alias-in").value });
    if (err) return snackbar(err);
    snackbar("The key is now used for every build."); reload();
  },
  "key-create": async () => {
    const o = { alias: $("#c-alias").value.trim(), password: $("#c-pass").value, name: $("#c-name").value.trim(),
      org: $("#c-org").value.trim(), country: $("#c-country").value.trim().toUpperCase(), years: $("#c-years").value, path: $("#c-path").value.trim() };
    if (!o.alias || !o.name || !o.path) return snackbar("Fill in the alias, your name and where to save the keystore.");
    if (o.password.length < 6) return snackbar("The password needs at least 6 characters.");
    if (o.password !== $("#c-pass2").value) return snackbar("The two passwords are not the same.");
    const err = await api("key_create", o);
    if (err) return snackbar(err);
    snackbar("Key created. Back up the keystore file now."); reload();
  },
  "remove-key": async () => { await api("key_remove"); reload(); },
  "show-secrets": () => showSecrets(),
  "copy-secret": async (el) => { await navigator.clipboard.writeText(S.secrets[el.dataset.name]); snackbar(`${el.dataset.name} copied`); },
  theme: async (el) => { S.data.settings.theme = el.dataset.theme; await api("set_setting", "theme", el.dataset.theme); render(); },
};

document.addEventListener("click", (ev) => {
  const el = ev.target.closest("[data-act]");
  if (el && actions[el.dataset.act] && !el.disabled) actions[el.dataset.act](el, ev);
});

document.addEventListener("input", (ev) => {
  const bind = ev.target.dataset.bind;
  if (!bind) return;
  if (bind === "search") { S.query = ev.target.value; const pos = ev.target.selectionStart; render(); const s = $("#search"); s.focus(); s.setSelectionRange(pos, pos); return; }
  if (bind === "number") { S.number = ev.target.value.replace(/\D/g, ""); return; }
  if (bind === "del-confirm") { $("#del-btn").disabled = ev.target.value.trim() !== S.edit.old; return; }
  if (S.view === "edit") {
    const f = S.edit.form;
    if (bind === "pullToRefresh") f.pullToRefresh = ev.target.checked;
    else f[bind] = bind === "appName" ? ev.target.value : ev.target.value.trim();
    if (bind === "folder" || bind === "applicationId") S.edit.touched[bind] = true;
    updateEdit();
  }
});

//========== BACKEND EVENTS ==========

window.onBackendEvent = (event, data) => {
  const b = S.build;
  const app = b && data && data.folder ? b.apps.find((a) => a.folder === data.folder) : null;
  switch (event) {
    case "build:app":
      if (!app) return;
      Object.assign(app, data);
      if (data.name) app.name_apk = data.name;
      app.name = S.data.apps.find((a) => a.folder === app.folder)?.appName || app.folder;
      render();
      break;
    case "build:step":
      if (app) { app.steps = data.steps; render(); }
      break;
    case "build:log":
      if (app) { app.line = data.line; const el = $("#log-line"); if (el) el.textContent = data.line; }
      break;
    case "build:finished":
      if (b) { b.finished = data; S.data.lastBuild = b.number; S.number = b.number + 1; render(); }
      break;
    case "setup:progress":
      S.setup = { ...data }; render();
      break;
    case "setup:done":
      S.setup = { label: "Done", percent: 100, done: true }; reload();
      break;
    case "setup:error":
      S.setup = { error: data }; render();
      break;
  }
};

// Keeps the elapsed time ticking while a build runs.
setInterval(() => { const el = $("#elapsed"); if (el && S.build) el.textContent = elapsed(S.build.started); }, 1000);

window.addEventListener("pywebviewready", reload);
