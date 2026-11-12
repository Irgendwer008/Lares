# Guardian Notifications

Tamper alerts and test messages go to channels the guardian sets up in the settings. Each
channel has its own message language. All connections are encrypted (HTTPS or SMTP over TLS)
and work through the user's VPN.

| Channel | Settings | Request |
|---|---|---|
| Telegram | bot token, chat id | `POST https://api.telegram.org/bot<token>/sendMessage` (JSON) |
| ntfy | server URL (default `https://ntfy.sh`), topic, optional token | `POST <server>` with JSON (`topic`, `title`, `message`, `tags`) |
| Email | SMTP host, port, SSL/TLS or STARTTLS, user, password, sender, recipient | SMTP: `AUTH PLAIN` or `AUTH LOGIN`, UTF-8 message |
| Webhook | HTTPS URL, optional bearer token | `POST <url>` with `{"app":"Lares","title":…,"message":…,"timestamp":…}` |
| Matrix | homeserver URL, room id (`!…:server`), access token | `PUT /_matrix/client/v3/rooms/{room}/send/m.room.message/{txnId}` |

## Implementation
- `NetworkAlertSender` (`platform`): `HttpsURLConnection`, HTTPS only, no redirects (credentials
  never go to another host), 15 s timeouts; email via `SmtpMailer`.
- `SmtpMailer`: implicit TLS (465) or STARTTLS (587) – plain connections are refused; certificate
  and host name verified; `AUTH PLAIN` if offered, otherwise `AUTH LOGIN`; subject RFC 2047,
  body base64 (UTF-8). Dialogue and message format are unit-tested (`SmtpTest`).
- Channels are part of the app state; secrets (bot token, access token, SMTP password) are
  encrypted with the Keystore. Exported rule files never contain them.
- Settings → Guardian notifications (greyed out while "Notify guardian" is off): add (free), edit
  / delete (needs the code), "Send test message" before saving, Telegram "Detect chat ID"
  (`getUpdates`), ntfy random topic generator.
- Texts are built in the channel's language (`AlertMessages`); the app bundle is not split by
  language, so all languages are installed.
- **Automatic sending:** every new tamper event starts `AlertWorker` (WorkManager, needs a
  network, exponential backoff from 30 s, retried until sent). Each channel remembers up to which
  event it got everything (`deliveredUpTo`), so a failing channel never causes duplicates on the
  others; several pending events go out as one message ("Lares: 3 protection events" with a list).
  An event counts as delivered once every channel has it. Events recorded while "Notify guardian"
  is off (or no channel exists) are never sent later.
- Message: what happened (e.g. "The clock was set forward by 125 minutes"), when, and the phone
  model – in the channel's language.

## Setup notes
- **Telegram**: each installation uses its **own bot**, created by the guardian with @BotFather
  (no shared bot token in the app – it would be extractable and abusable). The guardian sends
  `/start` to the bot; "Detect chat ID" reads it via `getUpdates`. Group chats have negative ids.
- **ntfy**: topics are public by name → the app suggests a long random topic.
- **Email**: ideally a separate account for Lares; many providers need an app password.

## Limitations
- After a force stop or uninstall the app can't send anything (a force stop is reported as a gap
  once Lares runs again). A server-side dead-man's switch
  (missing heartbeats → alert) would solve this but is out of scope for v1.
- Device-admin deactivation can be reported because Android calls the receiver first.
- WhatsApp has no API usable for private guardians and is therefore not offered.
