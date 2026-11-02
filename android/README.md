# Odysseus Android

The Odysseus web UI in an Android app, plus a native side that keeps track of
running replies when the page can't: screen off, app in the background, or a
bad connection.

## What it does

- **The web UI, full screen.** Everything the browser version has (markdown,
  images, tool cards, approvals, settings, file uploads, voice input) comes
  straight from your server, so the app never lags behind it.
- **Replies finish in the background.** Chats run as detached runs on the
  server. When the page starts a reply (or re-attaches to one), the app also
  follows it natively via `GET /api/chat/resume/{session}?ids=1&after=<id>`,
  under a foreground service ("Receiving a response…") so Android doesn't
  freeze it. Drops reconnect with backoff and pick up from the last event id;
  a network callback ends the wait as soon as connectivity returns.
- **"Response ready" notifications** when a reply finishes and you aren't
  looking at that chat. Tapping one opens the chat (`/#<session>`).
- **Downloads** go to the phone's Downloads folder, including `blob:` ones the
  page builds itself (exports, gallery).
- Links to other sites open in the phone's browser.

The native side signs in with the web page's own login cookie, so there is no
token or pairing step: open the app, enter the server URL, log in as usual.

## How the page and the app talk

A small script (`ShellScript.kt`) is injected at document start, only into the
server's own origin. It reports to the app through `OdysseusNative.postMessage`
(an `addWebMessageListener` bridge, also limited to the server's origin):

| Message | Sent when |
|---|---|
| `{t:"run", s}` | the page POSTs `/api/chat_stream` or GETs `/api/chat/resume/<s>` |
| `{t:"view", s}` | the chat in view changes (URL hash) |
| `{t:"file", name, mime, data}` | the page downloads a `blob:`/`data:` URL |

## Setup

Enter the server URL (`https://odysseus.example.com`, or `192.168.1.10:7000`
on the LAN, which gets `http://`). Long-press the app icon → **Change server**
to change it later; the error screen also offers it.

## Build

`cd android && ./gradlew testDebugUnitTest assembleRelease` (JDK 17+ and the
Android SDK). Android 10 (API 29) or newer.

## Limits

Notifications come from the app's own connection, not server push, so they only
fire for replies started or re-attached while the app was running. Deep Doze
can still pause the network for a screen-off phone; the app resumes as soon as
the network is back.
