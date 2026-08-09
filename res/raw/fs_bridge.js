/* ────────────────────────────────────────────────────────────────
   fs_bridge.js — File System Access API поверх Android SAF.

   Даёт странице в WebView ровно то, что умеет Chrome на десктопе:
   showDirectoryPicker(), FileSystemDirectoryHandle/FileSystemFileHandle,
   createWritable(), чтение/запись/удаление реальных файлов устройства
   и живое (всегда свежее) чтение размера и времени изменения —
   на этом строится синхронизация клиента с папкой.

   Скрипт подставляется приложением в каждый HTML из assets.
   ──────────────────────────────────────────────────────────────── */
(function () {
   "use strict";

   var NB = window.AndroidFS;
   var TOKEN = window.__AFS_T;
   if (!NB || !TOKEN || window.__AFS_INSTALLED) return;
   window.__AFS_INSTALLED = true;
   try { delete window.__AFS_T; } catch (e) { }

   var ORIGIN = location.origin;
   var CHUNK = 512 * 1024;
   var seq = 0;
   var pending = Object.create(null);

   /* ── связь с Java ───────────────────────────────────────────── */

   window.__AFS = {
      settle: function (id, json) {
         var p = pending[id];
         if (!p) return;
         delete pending[id];
         var r = null;
         try { r = JSON.parse(json); } catch (e) { }
         if (r && r.ok) p.res(r.v);
         else p.rej(mkErr(r));
      }
   };

   function mkErr(r) {
      var name = (r && r.e) || "InvalidStateError";
      var msg = (r && r.m) || name;
      try { return new DOMException(msg, name); }
      catch (e) { var err = new Error(msg); err.name = name; return err; }
   }

   function rpc(method, args) {
      return new Promise(function (res, rej) {
         var id = "q" + (++seq);
         pending[id] = { res: res, rej: rej };
         try { NB.call(TOKEN, id, method, JSON.stringify(args || {})); }
         catch (e) { delete pending[id]; rej(e); }
      });
   }

   function afsUrl(id) { return ORIGIN + "/__afs__?u=" + encodeURIComponent(id); }

   var TYPES = {
      html: "text/html", htm: "text/html", js: "application/javascript",
      mjs: "application/javascript", css: "text/css", json: "application/json",
      svg: "image/svg+xml", png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg",
      gif: "image/gif", webp: "image/webp", ico: "image/x-icon", bmp: "image/bmp",
      txt: "text/plain", md: "text/markdown", xml: "text/xml", csv: "text/csv",
      mp3: "audio/mpeg", wav: "audio/wav", ogg: "audio/ogg", mp4: "video/mp4",
      webm: "video/webm", pdf: "application/pdf", zip: "application/zip",
      woff: "font/woff", woff2: "font/woff2", ttf: "font/ttf", otf: "font/otf",
      wasm: "application/wasm"
   };

   function guessType(name) {
      var i = String(name || "").lastIndexOf(".");
      var ext = i > 0 ? name.slice(i + 1).toLowerCase() : "";
      return TYPES[ext] || "";
   }

   /* ── File поверх содержимого устройства (читается лениво) ───── */

   function AFSFile(meta, start, end, typeOverride) {
      this.__id = meta.id;
      this.__full = meta.size || 0;
      this.name = meta.name || "";
      this.lastModified = meta.mtime || Date.now();
      this.webkitRelativePath = "";
      var t = typeOverride;
      if (t === undefined || t === null) {
         t = meta.mime && meta.mime !== "application/octet-stream"
            ? meta.mime : guessType(this.name);
      }
      this.type = t || "";
      this.__start = start || 0;
      this.__end = (end === undefined || end === null) ? this.__full : end;
      if (this.__end > this.__full) this.__end = this.__full;
      if (this.__start > this.__end) this.__start = this.__end;
      this.size = this.__end - this.__start;
   }

   Object.defineProperty(AFSFile.prototype, Symbol.toStringTag, { value: "File" });
   Object.defineProperty(AFSFile.prototype, "lastModifiedDate", {
      get: function () { return new Date(this.lastModified); }
   });

   AFSFile.prototype.slice = function (start, end, type) {
      var s = this.__start, e = this.__end, len = this.size;
      var a = start === undefined ? 0 : (start < 0 ? Math.max(len + start, 0) : Math.min(start, len));
      var b = end === undefined ? len : (end < 0 ? Math.max(len + end, 0) : Math.min(end, len));
      if (b < a) b = a;
      var meta = { id: this.__id, name: this.name, size: this.__full, mtime: this.lastModified, mime: this.type };
      return new AFSFile(meta, s + a, s + b, type === undefined ? this.type : type);
   };

   AFSFile.prototype._blob = function () {
      var self = this;
      if (this.size === 0) return Promise.resolve(new Blob([], { type: this.type }));
      var init = { cache: "no-store" };
      if (this.__start > 0 || this.__end < this.__full) {
         init.headers = { Range: "bytes=" + this.__start + "-" + (this.__end - 1) };
      }
      return fetch(afsUrl(this.__id), init).then(function (r) {
         if (!r.ok && r.status !== 206) throw mkErr({ e: "NotFoundError", m: "Файл недоступен" });
         return r.blob();
      }).then(function (b) {
         return self.type ? b.slice(0, b.size, self.type) : b;
      });
   };

   AFSFile.prototype.arrayBuffer = function () {
      return this._blob().then(function (b) { return b.arrayBuffer(); });
   };
   AFSFile.prototype.bytes = function () {
      return this.arrayBuffer().then(function (ab) { return new Uint8Array(ab); });
   };
   AFSFile.prototype.text = function () {
      return this._blob().then(function (b) { return b.text(); });
   };
   AFSFile.prototype.toBlob = function () { return this._blob(); };
   AFSFile.prototype.toFile = function () {
      var self = this;
      return this._blob().then(function (b) {
         return new File([b], self.name, { type: self.type, lastModified: self.lastModified });
      });
   };
   AFSFile.prototype.stream = function () {
      var self = this;
      return new ReadableStream({
         start: function (c) {
            return self.arrayBuffer().then(function (ab) {
               if (ab.byteLength) c.enqueue(new Uint8Array(ab));
               c.close();
            }, function (e) { c.error(e); });
         }
      });
   };

   /* URL.createObjectURL для наших «файлов» — прямая ссылка на мост.
      Заодно запоминаем связку blob-URL → Blob: страница обычно вызывает
      revokeObjectURL сразу после click(), а сохранение асинхронное,
      поэтому содержимое нужно держать за руку до конца записи. */
   var blobUrls = new Map();

   try {
      var origCreate = URL.createObjectURL.bind(URL);
      var origRevoke = URL.revokeObjectURL.bind(URL);
      URL.createObjectURL = function (obj) {
         if (obj instanceof AFSFile) return afsUrl(obj.__id);
         var url = origCreate(obj);
         try { if (obj instanceof Blob) blobUrls.set(url, obj); } catch (e) { }
         return url;
      };
      URL.revokeObjectURL = function (url) {
         if (blobUrls.has(url)) {
            // Отпускаем не сразу — вдруг файл ещё пишется на диск.
            setTimeout(function () {
               blobUrls.delete(url);
               try { origRevoke(url); } catch (e) { }
            }, 120000);
            return;
         }
         try { origRevoke(url); } catch (e) { }
      };
   } catch (e) { }

   /* ── поток записи ───────────────────────────────────────────── */

   function toBlob(data) {
      if (data == null) return Promise.resolve(new Blob([]));
      if (data instanceof Blob) return Promise.resolve(data);
      if (data instanceof AFSFile) return data._blob();
      if (typeof data === "string") return Promise.resolve(new Blob([data]));
      if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) return Promise.resolve(new Blob([data]));
      return Promise.resolve(new Blob([String(data)]));
   }

   function blobToB64(blob) {
      return new Promise(function (res, rej) {
         if (!blob.size) { res(""); return; }
         var fr = new FileReader();
         fr.onload = function () {
            var s = String(fr.result);
            var i = s.indexOf(",");
            res(i < 0 ? "" : s.slice(i + 1));
         };
         fr.onerror = function () { rej(fr.error || new Error("read error")); };
         fr.readAsDataURL(blob);
      });
   }

   function AFSWritable(handle, data) {
      this.__h = handle;
      this.__data = data || new Blob([]);
      this.__pos = 0;
      this.__closed = false;
      this.locked = false;
   }

   AFSWritable.prototype.seek = function (pos) {
      this.__pos = pos | 0;
      return Promise.resolve();
   };

   AFSWritable.prototype.truncate = function (size) {
      var cur = this.__data;
      if (size <= cur.size) this.__data = cur.slice(0, size);
      else this.__data = new Blob([cur, new Uint8Array(size - cur.size)]);
      if (this.__pos > size) this.__pos = size;
      return Promise.resolve();
   };

   AFSWritable.prototype.write = function (data) {
      var self = this;
      if (this.__closed) return Promise.reject(mkErr({ e: "InvalidStateError", m: "Поток закрыт" }));
      if (data && typeof data === "object" && !(data instanceof Blob)
         && !(data instanceof ArrayBuffer) && !ArrayBuffer.isView(data)
         && typeof data.type === "string"
         && (data.type === "write" || data.type === "truncate" || data.type === "seek")) {
         if (data.type === "seek") return this.seek(data.position || 0);
         if (data.type === "truncate") return this.truncate(data.size || 0);
         if (typeof data.position === "number") this.__pos = data.position;
         data = data.data;
      }
      return toBlob(data).then(function (blob) {
         var cur = self.__data, pos = self.__pos, parts = [];
         if (pos > 0) parts.push(cur.slice(0, Math.min(pos, cur.size)));
         if (pos > cur.size) parts.push(new Uint8Array(pos - cur.size));
         parts.push(blob);
         if (pos + blob.size < cur.size) parts.push(cur.slice(pos + blob.size));
         self.__data = new Blob(parts);
         self.__pos = pos + blob.size;
      });
   };

   AFSWritable.prototype.close = function () {
      var self = this;
      if (this.__closed) return Promise.resolve();
      this.__closed = true;
      var blob = this.__data;
      var id = this.__h.__afsId;
      if (blob.size <= CHUNK) {
         return blobToB64(blob).then(function (b64) {
            return rpc("writeAll", { id: id, b64: b64 });
         }).then(function () { });
      }
      return rpc("writeOpen", { id: id }).then(function (sid) {
         var off = 0;
         function step() {
            if (off >= blob.size) return rpc("writeClose", { sid: sid });
            var part = blob.slice(off, Math.min(off + CHUNK, blob.size));
            off += CHUNK;
            return blobToB64(part).then(function (b64) {
               return rpc("writeChunk", { sid: sid, b64: b64 });
            }).then(step);
         }
         return step().catch(function (e) {
            return rpc("writeAbort", { sid: sid }).then(function () { throw e; });
         });
      }).then(function () { });
   };

   AFSWritable.prototype.abort = function () {
      this.__closed = true;
      return Promise.resolve();
   };

   /* ── дескрипторы ────────────────────────────────────────────── */

   function AFSHandle(o) {
      this.__afs = 1;
      this.__afsId = o.id;
      this.name = o.name;
      this.kind = o.kind;
   }

   AFSHandle.prototype.isSameEntry = function (other) {
      return Promise.resolve(!!other && other.__afsId === this.__afsId);
   };
   AFSHandle.prototype.queryPermission = function () {
      return rpc("permission", { id: this.__afsId });
   };
   AFSHandle.prototype.requestPermission = function () {
      return rpc("permission", { id: this.__afsId });
   };

   function AFSFileHandle(o) { AFSHandle.call(this, o); }
   AFSFileHandle.prototype = Object.create(AFSHandle.prototype);
   AFSFileHandle.prototype.constructor = AFSFileHandle;

   AFSFileHandle.prototype.getFile = function () {
      return rpc("meta", { id: this.__afsId }).then(function (m) { return new AFSFile(m); });
   };
   AFSFileHandle.prototype.createWritable = function (opts) {
      var self = this;
      if (opts && opts.keepExistingData) {
         return this.getFile().then(function (f) { return f._blob(); })
            .then(function (b) { return new AFSWritable(self, b); },
               function () { return new AFSWritable(self); });
      }
      return Promise.resolve(new AFSWritable(this));
   };
   AFSFileHandle.prototype.createSyncAccessHandle = function () {
      return Promise.reject(mkErr({ e: "NotSupportedError", m: "Недоступно в WebView" }));
   };

   function AFSDirHandle(o) { AFSHandle.call(this, o); }
   AFSDirHandle.prototype = Object.create(AFSHandle.prototype);
   AFSDirHandle.prototype.constructor = AFSDirHandle;

   function make(o) {
      return o && o.kind === "directory" ? new AFSDirHandle(o) : new AFSFileHandle(o);
   }

   AFSDirHandle.prototype.entries = function () {
      var id = this.__afsId;
      var list = null, i = 0;
      var it = {
         next: function () {
            var p = list ? Promise.resolve(list) : rpc("list", { id: id }).then(function (l) { list = l || []; return list; });
            return p.then(function (l) {
               if (i >= l.length) return { done: true, value: undefined };
               var e = l[i++];
               return { done: false, value: [e.name, make(e)] };
            });
         }
      };
      it[Symbol.asyncIterator] = function () { return it; };
      return it;
   };

   AFSDirHandle.prototype.values = function () {
      var src = this.entries();
      var it = {
         next: function () {
            return src.next().then(function (r) {
               return r.done ? r : { done: false, value: r.value[1] };
            });
         }
      };
      it[Symbol.asyncIterator] = function () { return it; };
      return it;
   };

   AFSDirHandle.prototype.keys = function () {
      var src = this.entries();
      var it = {
         next: function () {
            return src.next().then(function (r) {
               return r.done ? r : { done: false, value: r.value[0] };
            });
         }
      };
      it[Symbol.asyncIterator] = function () { return it; };
      return it;
   };

   AFSDirHandle.prototype[Symbol.asyncIterator] = function () { return this.entries(); };

   AFSDirHandle.prototype.getFileHandle = function (name, opts) {
      return rpc("child", {
         id: this.__afsId, name: name, kind: "file", create: !!(opts && opts.create)
      }).then(make);
   };

   AFSDirHandle.prototype.getDirectoryHandle = function (name, opts) {
      return rpc("child", {
         id: this.__afsId, name: name, kind: "directory", create: !!(opts && opts.create)
      }).then(make);
   };

   AFSDirHandle.prototype.removeEntry = function (name, opts) {
      return rpc("remove", {
         id: this.__afsId, name: name, recursive: !!(opts && opts.recursive)
      }).then(function () { });
   };

   /* Путь от этой папки до вложенного элемента (ограниченный обход). */
   AFSDirHandle.prototype.resolve = function (target) {
      if (!target || !target.__afsId) return Promise.resolve(null);
      var goal = target.__afsId;
      var self = this;
      if (goal === this.__afsId) return Promise.resolve([]);
      var visited = 0;
      function walk(dir, path, depth) {
         if (depth > 12 || visited > 4000) return Promise.resolve(null);
         return rpc("list", { id: dir.__afsId }).then(function (list) {
            var i = 0;
            function step() {
               if (i >= list.length) return null;
               var e = list[i++];
               visited++;
               if (e.id === goal) return path.concat([e.name]);
               if (e.kind !== "directory") return step();
               return walk(make(e), path.concat([e.name]), depth + 1).then(function (r) {
                  return r || step();
               });
            }
            return step();
         }, function () { return null; });
      }
      return walk(self, [], 0);
   };

   /* ── диалоги выбора ─────────────────────────────────────────── */

   window.showDirectoryPicker = function () {
      return rpc("pickDirectory", {}).then(make);
   };

   window.showOpenFilePicker = function (opts) {
      return rpc("pickFiles", { multiple: !!(opts && opts.multiple) }).then(function (arr) {
         return (arr || []).map(make);
      });
   };

   window.showSaveFilePicker = function (opts) {
      return rpc("saveFile", {
         suggestedName: (opts && opts.suggestedName) || "untitled.txt"
      }).then(make);
   };

   if (!window.FileSystemHandle) window.FileSystemHandle = AFSHandle;
   if (!window.FileSystemFileHandle) window.FileSystemFileHandle = AFSFileHandle;
   if (!window.FileSystemDirectoryHandle) window.FileSystemDirectoryHandle = AFSDirHandle;
   if (!window.FileSystemWritableFileStream) window.FileSystemWritableFileStream = AFSWritable;

   /* ── хранение дескрипторов в IndexedDB ──────────────────────── */

   function pack(v, d) {
      d = d || 0;
      if (!v || typeof v !== "object" || d > 8) return v;
      if (v instanceof AFSHandle) return { __afsH: { id: v.__afsId, name: v.name, kind: v.kind } };
      if (Array.isArray(v)) {
         var a = new Array(v.length);
         for (var i = 0; i < v.length; i++) a[i] = pack(v[i], d + 1);
         return a;
      }
      if (v instanceof Map) {
         var m = new Map();
         v.forEach(function (val, key) { m.set(pack(key, d + 1), pack(val, d + 1)); });
         return m;
      }
      if (v instanceof Set) {
         var s = new Set();
         v.forEach(function (val) { s.add(pack(val, d + 1)); });
         return s;
      }
      var proto = Object.getPrototypeOf(v);
      if (proto !== Object.prototype && proto !== null) return v;
      var o = {};
      for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) o[k] = pack(v[k], d + 1);
      return o;
   }

   function unpack(v, d) {
      d = d || 0;
      if (!v || typeof v !== "object" || d > 8) return v;
      if (v.__afsH) return make(v.__afsH);
      if (Array.isArray(v)) {
         var a = new Array(v.length);
         for (var i = 0; i < v.length; i++) a[i] = unpack(v[i], d + 1);
         return a;
      }
      if (v instanceof Map) {
         var m = new Map();
         v.forEach(function (val, key) { m.set(unpack(key, d + 1), unpack(val, d + 1)); });
         return m;
      }
      if (v instanceof Set) {
         var s = new Set();
         v.forEach(function (val) { s.add(unpack(val, d + 1)); });
         return s;
      }
      var proto = Object.getPrototypeOf(v);
      if (proto !== Object.prototype && proto !== null) return v;
      var o = {};
      for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) o[k] = unpack(v[k], d + 1);
      return o;
   }

   try {
      var store = window.IDBObjectStore && IDBObjectStore.prototype;
      if (store) {
         ["put", "add"].forEach(function (name) {
            var orig = store[name];
            if (typeof orig !== "function") return;
            store[name] = function (value, key) {
               return arguments.length > 1
                  ? orig.call(this, pack(value), key)
                  : orig.call(this, pack(value));
            };
         });
      }
      var rq = window.IDBRequest && Object.getOwnPropertyDescriptor(IDBRequest.prototype, "result");
      if (rq && rq.get) {
         Object.defineProperty(IDBRequest.prototype, "result", {
            configurable: true,
            enumerable: rq.enumerable,
            get: function () { return unpack(rq.get.call(this)); }
         });
      }
      var cw = window.IDBCursorWithValue
         && Object.getOwnPropertyDescriptor(IDBCursorWithValue.prototype, "value");
      if (cw && cw.get) {
         Object.defineProperty(IDBCursorWithValue.prototype, "value", {
            configurable: true,
            enumerable: cw.enumerable,
            get: function () { return unpack(cw.get.call(this)); }
         });
      }
   } catch (e) { }

   /* ── скачивание файлов в память устройства ──────────────────────
      В WebView ссылка <a download> с blob:-адресом ничего не делает,
      поэтому такие «скачивания» перехватываются здесь и уходят в Java,
      где открывается системный диалог выбора папки. */

   var DL = window.AndroidDL;
   var DL_CHUNK = 384 * 1024;

   function dlWarn(msg) {
      try { DL.note(TOKEN, String(msg)); } catch (e) { }
   }

   function nameFromUrl(url) {
      if (/^(blob|data|filesystem):/i.test(url)) return "file";
      try {
         var u = String(url).split("#")[0].split("?")[0];
         var seg = decodeURIComponent(u.slice(u.lastIndexOf("/") + 1));
         if (seg) return seg;
      } catch (e) { }
      return "file";
   }

   function dataUrlToBlob(url) {
      var comma = url.indexOf(",");
      var head = url.slice(5, comma);
      var body = url.slice(comma + 1);
      var isB64 = /;base64/i.test(head);
      var type = head.replace(/;base64/i, "").split(";")[0] || "application/octet-stream";
      var bin = isB64 ? atob(body) : decodeURIComponent(body);
      var arr = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      return new Blob([arr], { type: type });
   }

   function urlToBlob(url) {
      var known = blobUrls.get(url);
      if (known) return Promise.resolve(known);
      if (/^data:/i.test(url)) {
         try { return Promise.resolve(dataUrlToBlob(url)); }
         catch (e) { return Promise.reject(e); }
      }
      return fetch(url, { cache: "no-store", credentials: "include" }).then(function (r) {
         if (!r.ok && r.status !== 0 && r.status !== 206) throw new Error("HTTP " + r.status);
         return r.blob();
      });
   }

   /* Отдаёт содержимое порциями: большие ZIP-архивы через мост целиком не проходят. */
   function saveBlob(blob, name, mime) {
      if (!DL) return Promise.reject(new Error("мост загрузок недоступен"));
      var id = "";
      try { id = DL.begin(TOKEN, name || "file", mime || blob.type || ""); }
      catch (e) { return Promise.reject(e); }
      if (!id) return Promise.reject(new Error("не удалось начать сохранение"));

      var off = 0;
      function step() {
         if (off >= blob.size) { DL.finish(TOKEN, id); return true; }
         var part = blob.slice(off, Math.min(off + DL_CHUNK, blob.size));
         off += DL_CHUNK;
         return blobToB64(part).then(function (b64) {
            if (!DL.chunk(TOKEN, id, b64)) throw new Error("ошибка записи");
            return step();
         });
      }
      return Promise.resolve().then(step).catch(function (e) {
         try { DL.cancel(TOKEN, id); } catch (e2) { }
         throw e;
      });
   }

   function saveUrl(url, name, mime) {
      return urlToBlob(url).then(function (b) {
         return saveBlob(b, name || nameFromUrl(url), mime);
      });
   }

   function anchorSave(a) {
      if (!DL || !a) return false;
      var url = "";
      try { url = a.href || a.getAttribute("href") || ""; } catch (e) { }
      if (!url || /^(javascript|mailto|tel):/i.test(url) || url.charAt(0) === "#") return false;
      var name = "";
      try { name = a.getAttribute("download") || ""; } catch (e) { }
      saveUrl(url, name || nameFromUrl(url)).catch(function (e) {
         dlWarn("Не удалось скачать файл: " + (e && e.message ? e.message : e));
      });
      return true;
   }

   /* Клик по ссылке из кода: элемент часто даже не в документе,
      поэтому одного слушателя событий недостаточно. */
   try {
      var origClick = HTMLElement.prototype.click;
      HTMLElement.prototype.click = function () {
         try {
            if (DL && this.tagName === "A" && this.hasAttribute("download")
               && anchorSave(this)) return;
         } catch (e) { }
         return origClick.apply(this, arguments);
      };
   } catch (e) { }

   /* Обычный тап пользователя по ссылке со атрибутом download. */
   document.addEventListener("click", function (e) {
      if (!DL || e.defaultPrevented) return;
      var n = e.target;
      var a = null;
      while (n && n.nodeType === 1) {
         if (n.tagName === "A" && n.hasAttribute("download")) { a = n; break; }
         n = n.parentNode;
      }
      if (a && anchorSave(a)) e.preventDefault();
   }, true);

   /* ── публичное мини-API приложения ──────────────────────────── */

   window.IDEMobile = {
      native: true,
      platform: "android",
      fileUrl: afsUrl,
      chooseWebView: function () { try { NB.openWebViewSettings(TOKEN); } catch (e) { } },
      canSave: !!DL,
      /** Сохранить Blob/File в память устройства (спросит папку). */
      saveBlob: function (blob, name, mime) { return saveBlob(blob, name, mime); },
      /** Сохранить содержимое любого адреса: blob:, data:, https://…  */
      saveUrl: function (url, name, mime) {
         return saveUrl(url, name, mime).catch(function (e) {
            dlWarn("Не удалось скачать файл: " + (e && e.message ? e.message : e));
         });
      }
   };

   /* ── страница не должна ёрзать и масштабироваться ───────────── */

   function lockGestures() {
      // Масштабирование уже выключено в самом WebView и в meta viewport.
      // Здесь гасим только «резинку» прокрутки всей страницы.
      ["gesturestart", "gesturechange", "gestureend"].forEach(function (ev) {
         document.addEventListener(ev, function (e) {
            if (e.cancelable) e.preventDefault();
         }, { passive: false });
      });

      try {
         var st = document.createElement("style");
         st.textContent = "html,body{overscroll-behavior:none!important;}";
         (document.head || document.documentElement).appendChild(st);
      } catch (e) { }
   }

   if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", lockGestures);
   } else {
      lockGestures();
   }
})();
