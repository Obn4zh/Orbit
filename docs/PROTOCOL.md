# Orbit protocol v1

HTTP/1.1 по локальной сети, JSON. Одинаков на Windows (`windows/Orbit.Core/ReceiverServer.cs`) и Android TV (`android-tv/.../core/ReceiverServer.kt`).

- Порт: **47717** (если занят — 47718…47726, затем любой свободный).
- Обнаружение: DNS-SD `_orbit._tcp` (TXT: `id`, `v=1`, `platform`, `path=/`). Основной путь сопряжения — QR-код с URL.
- Авторизация: 8-символьный ключ сопряжения (алфавит без 0/O/1/I), заголовок `X-Orbit-Key` или параметр `?k=`. Сравнение за постоянное время. 20 неудач/мин с одного IP → `429`.
- Принимаются только адреса loopback, RFC 1918, link-local, IPv6 ULA — иначе `403 not_local`.

## Ошибки

```json
{ "error": "receiving_disabled", "message": "Receiving is turned off." }
```

| HTTP | `error` | Когда |
|---|---|---|
| 401 | `unauthorized` | неверный/нет ключа |
| 403 | `not_local` / `declined` / `expired` | не LAN / отклонено / не подтверждено за 90 с |
| 403 | `untrusted_network` | приёмник в недоверенной сети: Windows-профиль «Общественная» / Wi-Fi без пароля на ТВ |
| 404 | `not_found` | нет сессии/файла |
| 409 | `offset_mismatch` | `offset` ≠ уже принятому числу байт |
| 410 | `canceled` | отменено на приёмнике |
| 429 | `rate_limited` | перебор ключа |
| 503 | `receiving_disabled` | приём выключен |
| 507 | `insufficient_storage` | мало места |

## Эндпоинты

| Метод | Путь | Описание |
|---|---|---|
| GET | `/` | Страница отправителя (`shared/web/index.html`) |
| GET | `/api/v1/info` | `{name, deviceId, platform, version, receiving, requiresApproval}` |
| POST | `/api/v1/sessions` | `{sender, files:[{name,size,type}]}` → сессия. `status`: `pending` (ждёт подтверждения) или `accepted` |
| GET | `/api/v1/sessions/{id}` | статус сессии (клиент опрашивает раз в секунду, пока `pending`) |
| DELETE | `/api/v1/sessions/{id}` | отмена отправителем |
| GET | `/api/v1/sessions/{id}/files/{i}` | `{received, size, status, savedAs}` — для докачки |
| PUT | `/api/v1/sessions/{id}/files/{i}?offset=N` | тело = байты файла начиная с `N` |
| POST/PUT | `/?k=…` | То же, что `/api/v1/upload`: ссылка из QR-кода принимает загрузку как есть (шорткат «сканировать QR → отправить») |
| POST/PUT | `/api/v1/upload?k=…[&name=…][&from=…]` | Одним запросом (для iOS Shortcuts): `multipart/form-data` (имя из `filename`) или сырое тело. Запросы с одного IP объединяются в одну передачу, пока между ними < 60 с; подтверждение — один раз на такую серию |

Статусы сессии: `pending, accepted, active, completed, declined, expired, canceled, failed`. Файла: `queued, receiving, done, failed, canceled`.

## Докачка

1. Обрыв → приёмник сохраняет частичные данные (`*.orbitpart` на Windows, pending-элемент MediaStore на Android).
2. Клиент: `GET …/files/i` → `received`, затем `PUT …?offset=received` с оставшимися байтами.
3. Если тело закончилось раньше объявленного размера → `400 incomplete` с `received`.

## Хранение и безопасность имён

- Имя от отправителя очищается: берётся последний сегмент пути (`/` и `\`), удаляются управляющие символы, `<>:"|?*`, RTL-override, ведущие/хвостовые точки; зарезервированные имена Windows (`CON`, `LPT1`…) получают префикс `_`; длина ≤ 180.
- **Никогда не перезаписываем**: конфликт → `name (1).ext`. Итоговый путь проверяется на нахождение внутри папки загрузок.
- Файл становится видимым только после полного приёма (переименование `.orbitpart` / снятие `IS_PENDING`).
