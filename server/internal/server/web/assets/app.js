// hoard-server setup page: plain modules, no build step. Every server value goes into the DOM as text, never as HTML.
import { ICONS } from "./icons.js";
import { STRINGS } from "./i18n.js";

const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

let lang = store.get("lang") || ((navigator.language || "en").toLowerCase().startsWith("pt") ? "pt-BR" : "en");
if (!STRINGS[lang]) lang = "en";
const t = (key, vars = {}) => (STRINGS[lang][key] ?? STRINGS.en[key] ?? key).replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? "");

function icon(name) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 256 256");
  svg.setAttribute("fill", "currentColor");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const path = document.createElementNS(ns, "path");
  path.setAttribute("d", ICONS[name]);
  svg.append(path);
  return svg;
}

// h("div.row", {onclick}, child, "text", …)
function h(tag, attrs, ...children) {
  const [name, ...classes] = tag.split(".");
  const el = document.createElement(name);
  if (classes.length) el.className = classes.join(" ");
  if (attrs && (typeof attrs !== "object" || attrs instanceof Node || Array.isArray(attrs))) { children.unshift(attrs); attrs = null; }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "icon") el.append(icon(v));
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

async function api(method, path, body) {
  const opts = { method, headers: { "X-Hoard-Admin": "1" }, credentials: "same-origin" };
  if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
  let res;
  try { res = await fetch("api/" + path, opts); } catch { throw new Error(t("errorNetwork")); }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== "login" && path !== "setup") { route(true); }
  if (!res.ok) {
    const e = new Error(data.error?.message || t("errorGeneric", { code: res.status }));
    e.code = data.error?.code; e.status = res.status;
    throw e;
  }
  return data;
}

function toast(msg) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove("show"), 2600);
}

const bytes = (n) => {
  if (!n) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1000)));
  return new Intl.NumberFormat(lang, { maximumFractionDigits: i ? 1 : 0 }).format(n / 1000 ** i) + " " + u[i];
};
const num = (n) => new Intl.NumberFormat(lang).format(n || 0);
const when = (ms) => (ms ? new Intl.DateTimeFormat(lang, { dateStyle: "medium", timeStyle: "short" }).format(new Date(ms)) : t("never"));

// --- shell ---------------------------------------------------------------------------------------------------

const TABS = [
  ["status", "tabStatus", "pulse"], ["devices", "tabDevices", "device-mobile"],
  ["storage", "tabStorage", "hard-drives"], ["nas", "tabNas", "folder-simple"],
];
const page = () => document.getElementById("page");
let timers = [];

function applyChrome() {
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-t]").forEach((el) => { el.textContent = t(el.dataset.t); });
  document.querySelectorAll("[data-t-aria]").forEach((el) => { el.setAttribute("aria-label", t(el.dataset.tAria)); el.title = t(el.dataset.tAria); });
  document.querySelectorAll("[data-icon]").forEach((el) => { if (!el.firstChild) el.append(icon(el.dataset.icon)); });
  document.title = t("title");
}

function renderTabs(current, signedIn) {
  for (const id of ["tabs", "tabs-mobile"]) {
    const nav = document.getElementById(id);
    nav.hidden = !signedIn;
    nav.replaceChildren(...TABS.map(([key, label, ic]) =>
      h("a.tab", { href: "#" + key, "aria-current": key === current ? "page" : null, icon: ic }, t(label))));
  }
  document.getElementById("signout").hidden = !signedIn;
}

async function route(forceState) {
  timers.forEach(clearInterval);
  timers = [];
  let state;
  try { state = await api("GET", "state"); } catch (e) { page().replaceChildren(h("p.error", e.message)); return; }
  if (state.setup) { renderTabs("", false); return renderSetup(); }
  if (!state.signedIn || forceState === true) { renderTabs("", false); return renderSignIn(); }
  const key = (location.hash || "#status").slice(1);
  renderTabs(key, true);
  const view = { status: renderStatus, devices: renderDevices, storage: renderStorage, nas: renderNas }[key] || renderStatus;
  try { await view(); } catch (e) { page().replaceChildren(h("p.error", e.message)); }
}

function field(label, attrs, hint) {
  const id = "f-" + Math.random().toString(36).slice(2);
  return [h("label", { for: id }, label), h("input.input", { id, ...attrs }), hint ? h("p.hint", hint) : null];
}

function renderSetup() {
  const err = h("p.error", { role: "alert" });
  const form = h("form.form", {
    onsubmit: async (ev) => {
      ev.preventDefault();
      const f = new FormData(form);
      if (f.get("password") !== f.get("again")) { err.textContent = t("passwordsDiffer"); return; }
      try { await api("POST", "setup", { code: f.get("code"), password: f.get("password") }); location.hash = "#storage"; route(); } catch (e) { err.textContent = e.message; }
    },
  },
  field(t("setupCode"), { name: "code", required: true, autocomplete: "one-time-code", inputmode: "numeric", class: "input mono" }, t("setupCodeHint")),
  field(t("password"), { name: "password", type: "password", required: true, minlength: 12, autocomplete: "new-password" }, t("passwordHint")),
  field(t("passwordAgain"), { name: "again", type: "password", required: true, minlength: 12, autocomplete: "new-password" }),
  h("button.button.primary", { type: "submit" }, t("setupButton")), err);
  page().replaceChildren(h("div.section", h("h2", t("setupTitle")), h("p.muted", t("setupIntro")), form));
}

function renderSignIn() {
  const err = h("p.error", { role: "alert" });
  const form = h("form.form", {
    onsubmit: async (ev) => {
      ev.preventDefault();
      try { await api("POST", "login", { password: new FormData(form).get("password") }); route(); } catch (e) { err.textContent = e.message; }
    },
  }, field(t("password"), { name: "password", type: "password", required: true, autocomplete: "current-password" }),
  h("button.button.primary", { type: "submit" }, t("signIn")), err);
  page().replaceChildren(h("div.section", h("h2", t("signInTitle")), h("p.muted", t("signInIntro")), form));
}

// --- status ---------------------------------------------------------------------------------------------------

async function renderStatus() {
  const s = await api("GET", "status");
  const c = s.counts, b = s.blobs || {};
  const metric = (label, value) => h("div.metric", h("div.eyebrow", label), h("div.value", value));
  const errors = [...(s.folderErrors || []).map((f) => `${f.folder}: ${t("state" + cap(f.state))}`),
    ...(s.errors || []).slice().reverse().map((e) => `${when(e.at)} · ${e.route} · ${e.code || "internal"}`)];
  const settingsErr = h("p.error", { role: "alert" });
  const name = h("input.input", { value: s.settings.serverName, maxlength: 60 });
  const url = h("input.input", { value: s.settings.publicUrl || location.origin, type: "url" });
  const proxy = h("input", { type: "checkbox", checked: s.settings.adminViaProxy, disabled: !s.direct });
  const pwErr = h("p.error", { role: "alert" });
  const pwForm = h("form.form", {
    onsubmit: async (ev) => {
      ev.preventDefault();
      const f = new FormData(pwForm);
      try { await api("PUT", "password", { current: f.get("current"), password: f.get("password") }); pwForm.reset(); pwErr.textContent = ""; toast(t("saved")); } catch (e) { pwErr.textContent = e.message; }
    },
  }, field(t("currentPassword"), { name: "current", type: "password", required: true, autocomplete: "current-password" }),
  field(t("newPassword"), { name: "password", type: "password", required: true, minlength: 12, autocomplete: "new-password" }, t("passwordHint")),
  h("button.button", { type: "submit" }, t("changePassword")), pwErr);

  page().replaceChildren(
    h("div.section",
      h("div.section-head", h("h2", t("statusTitle")), s.indexing ? h("span.badge.warn", t("indexingNow")) : null),
      s.storageConfigured ? null : h("p", h("span.badge.warn", t("backupNotSet"))),
      h("div.metrics",
        metric(t("photos"), num(c.photos)), metric(t("versions"), num(c.versions)), metric(t("devices"), num(c.devices)),
        metric(t("originals"), `${num(b.originalCount)} · ${bytes(b.originalBytes)}`),
        metric(t("previews"), `${num(b.previewCount)} · ${bytes(b.previewBytes)}`),
        metric(t("nasFiles"), num(c.nasFiles)), metric(t("pendingUploads"), num(c.pendingUploads)))),
    h("div.section", h("h2", t("recentErrors")),
      errors.length ? h("div.list", errors.map((e) => h("div.row", icon("warning-circle"), h("div.main.mono", e)))) : h("p.muted", t("noErrors"))),
    h("div.section", h("h2", t("settingsTitle")),
      h("div.form",
        h("label", t("serverName")), name,
        h("label", t("publicUrl")), url, h("p.hint", t("publicUrlHint")),
        h("label.switch", proxy, h("span", t("allowProxy"), h("p.hint", s.direct ? t("allowProxyHint") : t("allowProxyLocalOnly")))),
        h("button.button.primary", {
          onclick: async () => {
            const body = { serverName: name.value, publicUrl: url.value };
            if (s.direct) body.adminViaProxy = proxy.checked;
            try { await api("PUT", "settings", body); settingsErr.textContent = ""; toast(t("saved")); } catch (e) { settingsErr.textContent = e.message; }
          },
        }, t("save")), settingsErr)),
    h("div.section", h("h2", t("changePassword")), pwForm),
    h("div.section", h("p.muted.mono", `${t("version")} ${s.version} · ${t("serverId")} ${s.serverId}`)));
}

const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

// --- devices ----------------------------------------------------------------------------------------------------

async function renderDevices() {
  const { devices } = await api("GET", "devices");
  const status = await api("GET", "status");
  const pairArea = h("div");
  // ADR 0017: devices (app installations) grouped by the physical device they run on.
  const groups = new Map();
  for (const d of [...devices].reverse()) {
    const id = d.physicalDeviceId || d.id;
    if (!groups.has(id)) groups.set(id, []);
    groups.get(id).push(d);
  }
  const groupName = (members) => (members.find((d) => !d.revokedAt) || members[0]).name;
  const move = async (d, to) => {
    try { await api("POST", `devices/${encodeURIComponent(d.id)}/physical`, { physicalDeviceId: to || null }); route(); }
    catch (e) { toast(e.code === "conflict" ? t("moveSameApp") : e.message); route(); }
  };
  const moveSelect = (d) => {
    const own = d.physicalDeviceId || d.id;
    const others = [...groups.entries()].filter(([id, ms]) => id !== own && ms.some((m) => !m.revokedAt));
    if (!others.length && groups.get(own).length < 2) return null;
    const sel = h("select.select.compact", { "aria-label": t("moveTo"), onchange: () => move(d, sel.value === "__own" ? "" : sel.value) },
      h("option", { value: own, selected: true }, t("moveTo")),
      others.map(([id, ms]) => h("option", { value: id }, groupName(ms))),
      groups.get(own).length > 1 ? h("option", { value: "__own" }, t("moveOwn")) : null);
    return sel;
  };
  const appName = (d) => (d.app ? `${d.app[0].toUpperCase()}${d.app.slice(1)}` : null);
  const deviceRow = (d) => h("div.row",
    icon("device-mobile"),
    h("div.main",
      h("div.name", [appName(d), d.name].filter(Boolean).join(" · ")),
      h("div.meta", [d.platform, d.appVersion].filter(Boolean).join(" · "), " · ", `${t("lastSync")}: ${when(d.lastSyncAt)}`, " · ", `${t("added")}: ${when(d.createdAt)}`)),
    h("div.actions", d.revokedAt ? h("span.badge.idle", t("revoked")) : [moveSelect(d), h("button.button.danger", {
      onclick: async () => {
        if (!confirm(t("revokeConfirm"))) return;
        try { await api("POST", `devices/${encodeURIComponent(d.id)}/revoke`); route(); } catch (e) { toast(e.message); }
      },
    }, t("revoke"))]));
  const rows = [...groups.values()].reverse().map((members) => {
    const live = members.find((d) => !d.revokedAt) || members[0];
    const model = [live.manufacturer, live.model].filter(Boolean).join(" ");
    return h("div.list",
      h("div.group-head", h("span.eyebrow", t("physicalDevice")), h("span.name", groupName(members)), model ? h("span.muted", model) : null),
      members.map(deviceRow));
  });
  const url = h("input.input", { type: "url", value: status.settings.publicUrl || location.origin });
  const startPair = async () => {
    try {
      const p = await api("POST", "pairings", { url: url.value });
      const note = h("p.hint", t("pairExpires", { time: new Intl.DateTimeFormat(lang, { timeStyle: "short" }).format(new Date(p.expiresAt)) }));
      pairArea.replaceChildren(h("div.pair",
        h("img", { src: p.qr, alt: t("pairTitle"), width: 240, height: 240 }),
        h("div.text", h("p", t("pairScan")), h("p.mono.muted", url.value), note,
          h("button.button", { onclick: () => { timers.forEach(clearInterval); pairArea.replaceChildren(); } }, t("close")))));
      timers.push(setInterval(async () => {
        try {
          const st = await api("GET", `pairings/${encodeURIComponent(p.id)}`);
          if (st.used) { toast(t("pairDone", { name: st.deviceName || "" })); route(); }
          else if (st.expired) { timers.forEach(clearInterval); note.textContent = t("pairExpired"); }
        } catch { /* keep polling */ }
      }, 2000));
    } catch (e) { pairArea.replaceChildren(h("p.error", e.message)); }
  };
  page().replaceChildren(
    h("div.section",
      h("div.section-head", h("h2", t("devicesTitle"))),
      h("p.muted", t("devicesIntro")),
      rows.length ? h("div.groups", rows) : h("p.empty", t("noDevices"))),
    h("div.section", h("h2", t("pairTitle")),
      h("div.form", h("label", t("publicUrl")), url, h("p.hint", t("pairUrlHint")),
        h("button.button.primary", { onclick: startPair, icon: "qr-code" }, t("showQr"))),
      pairArea));
}

// --- storage ----------------------------------------------------------------------------------------------------

async function renderStorage() {
  const s = await api("GET", "storage");
  const b = s.blobs || {};
  const usedByServer = (b.originalBytes || 0) + (b.previewBytes || 0);
  const current = s.backupRoot
    ? h("div.list", h("div.row", icon("hard-drives"),
      h("div.main", h("div.name.mono", s.backupRoot),
        s.available ? null : h("span.badge.bad", t("unavailable")),
        s.totalBytes ? [h("div.bar", h("span")), h("div.meta", t("free", { free: bytes(s.freeBytes), total: bytes(s.totalBytes) }), " · ", `${t("used")}: ${bytes(usedByServer)}`)] : null)))
    : h("p", h("span.badge.warn", t("backupNotSet")));
  const bar = current.querySelector?.(".bar > span");
  if (bar && s.totalBytes) bar.style.width = `${Math.round(((s.totalBytes - s.freeBytes) / s.totalBytes) * 100)}%`;
  const rows = (s.candidates || []).map((c) => h("div.row", icon("folder-simple"),
    h("div.main", h("div.name.mono", c.path), h("div.meta", c.writable ? "" : t("readOnly") + " · ", c.totalBytes ? t("free", { free: bytes(c.freeBytes), total: bytes(c.totalBytes) }) : "")),
    h("div.actions", c.path === s.backupRoot ? h("span.badge.ok", t("current")) : h("button.button", {
      disabled: !c.writable || (s.locked && s.backupRoot),
      onclick: async () => { try { await api("PUT", "storage", { path: c.path }); route(); } catch (e) { toast(e.message); } },
    }, t("choose")))));
  page().replaceChildren(
    h("div.section", h("h2", t("storageTitle")), h("p.muted", t("storageIntro")), current,
      s.locked && s.backupRoot ? h("p.hint", t("storageLocked")) : null),
    s.previews ? renderPreviews(s.previews) : null,
    h("div.section", h("h2", t("available")), rows.length ? h("div.list", rows) : h("p.empty", t("noCandidates"))));
}

// Gallery previews (ADR 0015): what is made, its size, the queue.
function renderPreviews(p) {
  const metric = (label, value, hint) => h("div.metric", h("div.eyebrow", label), h("div.value", value), hint ? h("div.meta", hint) : null);
  const img = p.jobs.image || {}, proxy = p.jobs.proxy || {};
  const queued = (img.pending || 0) + (img.running || 0) + (proxy.pending || 0) + (proxy.running || 0);
  const failed = (img.failed || 0) + (proxy.failed || 0);
  const errors = Object.entries(p.errors || {}).map(([e, n]) => h("div.row", icon("warning-circle"), h("div.main.mono", `${num(n)} · ${e}`)));
  return h("div.section",
    h("div.section-head", h("h2", t("previewsTitle")), p.toolsAvailable ? null : h("span.badge.warn", t("previewsNoTools"))),
    h("p.muted", t("previewsIntro")),
    h("div.metrics",
      metric(t("previewsImages"), `${num(img.ready)} · ${bytes(img.bytes)}`, img.stale ? t("previewsStale", { n: num(img.stale) }) : null),
      metric(t("previewsProxies"), `${num(proxy.ready)} · ${bytes(proxy.bytes)}`, proxy.stale ? t("previewsStale", { n: num(proxy.stale) }) : null),
      metric(t("previewsQueue"), num(queued), t("previewsRunning", { n: num((img.running || 0) + (proxy.running || 0)) })),
      metric(t("previewsFailed"), num(failed))),
    h("p.meta.mono", p.dir, p.totalBytes ? ` · ${t("free", { free: bytes(p.freeBytes), total: bytes(p.totalBytes) })}` : ""),
    errors.length ? h("div.list", errors) : null);
}

// --- NAS ---------------------------------------------------------------------------------------------------------

async function renderNas() {
  const s = await api("GET", "nas");
  const badge = (st) => h("span.badge." + ({ ready: "ok", indexing: "warn", pending: "idle", error: "bad", unavailable: "bad" }[st] || "idle"), t("state" + cap(st)));
  const indexedPaths = new Set(s.folders.map((f) => f.path));
  const rows = s.folders.map((f) => h("div.row", icon("folder-simple"),
    h("div.main", h("div.name", f.name), h("div.meta", h("span.mono", f.path), " · ", t("files", { n: num(f.fileCount) }), " · ", when(f.indexedAt))),
    badge(f.state),
    h("div.actions", h("button.button", {
      onclick: async () => {
        if (!confirm(t("removeConfirm"))) return;
        try { await api("DELETE", `nas/${encodeURIComponent(f.id)}`); route(); } catch (e) { toast(e.message); }
      },
    }, t("remove")))));
  const candidates = (s.candidates || []).filter((c) => !indexedPaths.has(c.path)).map((c) => h("div.row", icon("folder-simple"),
    h("div.main", h("div.name.mono", c.path), h("div.meta", c.writable ? "" : t("readOnly"))),
    h("div.actions", h("button.button", {
      icon: "plus",
      onclick: async () => { try { await api("POST", "nas", { path: c.path }); route(); } catch (e) { toast(e.message); } },
    }, t("add")))));
  page().replaceChildren(
    h("div.section",
      h("div.section-head", h("h2", t("nasTitle")),
        h("button.button", { icon: "arrows-clockwise", onclick: async () => { await api("POST", "nas/reindex"); toast(t("indexingNow")); setTimeout(route, 1500); } }, t("reindex"))),
      h("p.muted", t("nasIntro")),
      rows.length ? h("div.list", rows) : h("p.empty", t("noFolders"))),
    h("div.section", h("h2", t("available")), candidates.length ? h("div.list", candidates) : h("p.empty", t("noCandidates"))));
  if (s.indexing || s.folders.some((f) => f.state === "indexing" || f.state === "pending")) timers.push(setInterval(route, 3000));
}

// --- boot ---------------------------------------------------------------------------------------------------------

function applyTheme(v) {
  const root = document.documentElement;
  root.classList.toggle("light", v === "light");
  root.classList.toggle("dark", v === "dark");
}

const langSelect = document.getElementById("lang");
const themeSelect = document.getElementById("theme");
langSelect.value = lang;
themeSelect.value = store.get("theme") || "system";
applyTheme(themeSelect.value);
langSelect.addEventListener("change", () => { lang = langSelect.value; store.set("lang", lang); applyChrome(); route(); });
themeSelect.addEventListener("change", () => { store.set("theme", themeSelect.value); applyTheme(themeSelect.value); });
document.getElementById("signout").addEventListener("click", async () => { await api("POST", "logout").catch(() => {}); route(); });
window.addEventListener("hashchange", () => route());
applyChrome();
route();
