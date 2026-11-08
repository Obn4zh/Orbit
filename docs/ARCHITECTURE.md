# Архитектура Orbit

```
 iPhone                                   Локальная Wi-Fi сеть                 Приёмники
┌──────────────────────────┐                                         ┌──────────────────────────────┐
│ Safari: страница Orbit   │──QR (http://IP:47717/?k=KEY)──────────▶ │ Windows: Orbit.exe (WPF)     │
│  (shared/web/index.html) │   POST /sessions, PUT …/files/i         │  Orbit.Core: Kestrel + TM    │
│                          │                                         │  DNS-SD через dnsapi.dll     │
│ Shortcuts: Share Sheet   │──POST /api/v1/upload (multipart)──────▶ ├──────────────────────────────┤
│  «Send to Orbit»         │                                         │ Android TV: Orbit (Compose)  │
│                          │                                         │  Ktor CIO + TM, NsdManager   │
│ (будущее) iOS-приложение │──Bonjour _orbit._tcp + тот же API──────▶│  MediaStore / SAF            │
└──────────────────────────┘                                         └──────────────────────────────┘
```

## Решения (ADR кратко)

| Решение | Выбор | Почему | Альтернативы |
|---|---|---|---|
| Совместимость с iPhone | Веб-страница + Shortcuts, без iOS-приложения в MVP | Нативный AirDrop недоступен ([AIRDROP-FEASIBILITY.md](AIRDROP-FEASIBILITY.md)); Safari и Shortcuts есть на каждом iPhone; iOS-приложение нельзя собрать без Mac | Нативное приложение с Share Extension — следующий шаг |
| Транспорт | HTTP/1.1, тело файла «как есть» (`PUT` raw), без multipart в основном пути | Стриминг без буферизации, тривиальная докачка через `offset`, Safari XHR даёт прогресс загрузки | WebSocket (сложнее докачка, нет пользы), TCP-протокол (браузер не умеет) |
| Шифрование | Нет TLS в MVP | Safari не доверит самоподписанному сертификату без ручной установки профиля → ломает «ноль настройки». Компенсация: только LAN, ключ сопряжения, подтверждение | HTTPS с установкой профиля; будущий iOS-клиент — TLS с pinning по QR |
| Сопряжение | Ключ в QR-коде, ротация вручную | Сканирование камерой iPhone — самый быстрый путь; ключ отсекает случайных клиентов сети | PIN вручную |
| Windows UI | **WPF .NET 10 + Fluent ThemeMode** | Нативный вид Windows 11 (Mica, светлая/тёмная), собирается `dotnet build` без Visual Studio, стабильно | WinUI 3 (тяжелее деплой, нужен Windows App SDK runtime), Electron (300 МБ) |
| Windows сервер | Kestrel внутри приложения | Без прав администратора (HttpListener требует urlacl), высокий throughput (≈250 МБ/с loopback) | HttpListener |
| Обнаружение Windows | `DnsServiceRegister` (dnsapi.dll) | Системный mDNS-респондер, без конфликта за порт 5353 | Свой mDNS (конфликт с Windows) |
| Android TV UI | Kotlin + **Compose for TV** (`tv-material 1.0`) | Фокус-навигация пультом, эффекты фокуса из коробки | Leanback (устарел) |
| Android сервер | Ktor CIO | Корутины, стриминг канала тела, multipart | NanoHTTPD (буферизует тела во временные файлы) |
| Хранилище TV | **MediaStore** (Download/Orbit или по типам), SAF только если есть настоящий пикер | На проверенном Google TV `ACTION_OPEN_DOCUMENT_TREE` ведёт на заглушку `TvFrameworkPackageStubs` — выбор папки невозможен. MediaStore на Android 10+ не требует разрешений | Прямые пути (запрещены scoped storage) |
| Фон на TV | Foreground service `dataSync` + Wi-Fi lock | Передача не рвётся по кнопке Home | WorkManager (не для входящих соединений) |

## Модули

```
shared/web/            index.html (страница отправителя), icon.svg/png — встраиваются в оба приложения
windows/Orbit.Core/    протокол, TransferManager, санитайзер, настройки (без UI, покрыт тестами)
windows/Orbit.Windows/ WPF: MainWindow, ViewModels, трей, mDNS, QR
windows/Orbit.Core.Tests/  44 теста (xUnit): протокол end-to-end на реальном Kestrel
android-tv/app/        core/ (Receiver, TransferManager, ReceiverServer, Storage, Settings), ui/, service/
tools/orbit-send.mjs   CLI-отправитель для тестов (докачка, обрыв, multipart)
```

## Поток передачи

1. Отправитель `POST /sessions` со списком файлов → приёмник проверяет приём включён, место на диске, лимиты.
2. Если включено «Спрашивать» — окно подтверждения (90 с), отправитель опрашивает статус.
3. При принятии резервируются уникальные имена (без перезаписи).
4. `PUT` каждого файла стримится на диск через буфер 256–512 КБ; пауза = приёмник перестаёт читать сокет (TCP backpressure); отмена = закрытие и удаление частичного файла.
5. После последнего байта файл «публикуется» (rename / `IS_PENDING=0`), событие → уведомление/история.
