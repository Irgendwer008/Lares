# Guardian Notifications

All channels are HTTPS-based → work through the user's VPN. Delivery is queued via
WorkManager and retried when offline. Each channel has its own message language.

Interface:
```kotlin
interface AlertChannel {
    suspend fun send(event: TamperEvent, text: String): Result<Unit>
    suspend fun test(): Result<Unit>   // "Send test message" in setup
}
```

| Channel | Config | Request |
|---|---|---|
| Telegram | bot token, chat id | `POST https://api.telegram.org/bot<token>/sendMessage` |
| ntfy | server URL (default `https://ntfy.sh`), topic, optional token | `POST <server>/<topic>` |
| Webhook | URL, optional headers, JSON template | `POST <url>` |
| Matrix | homeserver URL, access token, room id | `PUT /_matrix/client/v3/rooms/{room}/send/m.room.message/{txnId}` |

## Setup notes
- **Telegram**: each installation uses its **own bot** (created via @BotFather, ideally by
  the guardian). No shared bot token is shipped in the app (would be extractable / abusable).
  The guardian sends `/start`; the app reads the chat id via `getUpdates`.
- **ntfy**: suggest a long random topic name (topics are public by name).
- Tokens are stored encrypted (Keystore).
- Changing or removing a channel is loosening → requires code.

## Limitations
- After force stop / uninstall the app can't send anything. A server-side dead-man's
  switch (missing heartbeats → alert) would solve this but is out of scope for v1.
- Device-admin deactivation is reported because Android calls the receiver first.
