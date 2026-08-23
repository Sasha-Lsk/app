# IDE Mobile

## Конвертер V2Ray URL

`index.html` — автономный браузерный конвертер для XML и JSON с вложенными
V2Ray-конфигурациями. Он принимает файл или вставленный текст, находит записи
`follow`/`soon` (а также `protocol`/`settings`) и формирует список ссылок
`vless://`, `vmess://`, `trojan://` и `ss://`. Все преобразования выполняются
локально в браузере; результат можно отредактировать, скопировать или скачать
как `v2ray-urls.txt`.

Оболочка Android для веб-клиента IDE. Пакет `com.app.idemobile`, сборка — AIDE (обычный
Eclipse-проект, без Gradle и без внешних библиотек).

## Сборка в AIDE
1. Распаковать архив в память телефона.
2. AIDE → «Открыть проект» → папка `IDEMobile` (файл `AndroidManifest.xml`).
3. Run. Минимум Android 5.0 (API 21), целевая — API 28.

## Что внутри
- `assets/` — распакованный веб-клиент (`index.html` + `css/js/fonts/…`).
  Приложение само находит точку входа: `index.html`, а если его нет — первый HTML-файл.
  Работает и с одним html-файлом, и с комплектом html+css+js.
- `res/drawable-nodpi/splash.jpg` — заставка, растягивается на весь экран, 3 секунды.
- `res/mipmap-*/ic_launcher.png` — иконка.
- `res/raw/fs_bridge.js` — File System Access API поверх Android SAF.

## Как это работает
- Содержимое `assets` отдаётся не через `file://`, а через
  `https://appassets.androidplatform.net` — иначе в WebView не работают
  localStorage, IndexedDB, service worker и работа с папками.
- При первом запуске приложение спрашивает, каким WebView пользоваться
  (системный, WebView Dev/Beta/Canary, Chrome…). Сам движок переключает Android,
  поэтому приложение открывает нужный системный экран; выбор запоминается.
  Позже поменять — пункт меню «Движок WebView…» или `IDEMobile.chooseWebView()` из JS.
- В каждый HTML на лету подставляется мост, который даёт странице
  `showDirectoryPicker()`, `showOpenFilePicker()`, `showSaveFilePicker()`,
  `FileSystemDirectoryHandle`/`FileSystemFileHandle`, `createWritable()` —
  то же API, что в браузерах на Chromium. Папка выбирается системным
  диалогом Android, доступ сохраняется между запусками, чтение всегда свежее,
  поэтому клиент синхронизируется с реальными файлами устройства.
- Зум и «резинка» прокрутки выключены: страница не ёрзает.
- «Скачать» из проводника IDE работает: ссылки `<a download>` с `blob:`/`data:`
  адресами (обычный WebView их молча игнорирует) перехватываются мостом,
  содержимое передаётся в приложение по частям и сохраняется через системный
  диалог Android «Сохранить как» — папка и имя выбираются вручную. Если диалога
  на устройстве нет, файл пишется в «Загрузки» (для этого спрашивается доступ
  к памяти — `WRITE_EXTERNAL_STORAGE`). Из JS доступно
  `IDEMobile.saveBlob(blob, имя)` и `IDEMobile.saveUrl(url, имя)`.
