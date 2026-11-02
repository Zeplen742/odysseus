# Odysseus Android

A small native client for a self-hosted Odysseus server. It exists to make remote
chat **survive bad connections**: a dropped stream resumes where it left off
instead of failing.

## How reliability works

- Chats run as detached runs on the server; closing the connection never stops them.
- The app asks for `?ids=1`, so every SSE event carries a sequence number (`id:`).
- If the link drops (read timeout, no network, tunnel reset), `TurnRunner` reconnects
  with backoff to `GET /api/chat/resume/{session}?after=<last id>` and receives only
  the events it missed. It never re-POSTs the message.
- Opening the app (or a chat) re-attaches to any run that is still live.
- While a response is streaming, a foreground service ("Receiving a response…") keeps
  Android from freezing the app when the screen turns off or you switch apps. Turns run
  in an app-wide scope, so swiping the app away from recents does not cancel them.
- Leaving a chat with Back does not cancel it. When the response finishes and you are
  not looking at that chat, you get a "Response ready" notification; tapping it opens
  the chat. A lost connection that cannot be recovered notifies too.
- A network callback ends the reconnect wait early the moment connectivity returns.
- When a turn ends, the saved history from the server replaces the streamed text.

## Pairing

1. In a browser logged in as admin, open `https://<your-server>/api/companion/pair`
   and generate a code.
2. In the app enter your server URL (your Cloudflare hostname works) and paste the
   token, or the whole pairing JSON.

## Build

CI builds a debug APK on every change to `android/` (Actions → Android → artifact
`odysseus-debug-apk`). Locally: `cd android && ./gradlew testDebugUnitTest assembleDebug`
(needs JDK 17 and the Android SDK). Install the APK on your phone and allow
"install unknown apps" for your browser/file manager.

## Not in v1

No QR scanner, attachments, tool-approval prompts or markdown rendering. Notifications
come from the app's own connection, not server push, so they only fire while the app's
process is alive (during a stream). Deep Doze can still pause the network for a
screen-off phone; the app resumes as soon as the network is back.
