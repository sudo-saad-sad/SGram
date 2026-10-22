<div align="center">

# SGram (Matrix-Telegram)

**The power and elegance of Telegram's native Android UI — powered 100% by the decentralized Matrix protocol.**

[![Latest Release](https://img.shields.io/github/v/release/sudo-saad-sad/SGram?color=0088cc&label=Latest%20Release&style=for-the-badge)](https://github.com/sudo-saad-sad/SGram/releases/latest)
[![Protocol](https://img.shields.io/badge/Protocol-Matrix%20v1.11%20%7C%20Matrix%202.0-0ebd8c?style=for-the-badge&logo=matrix)](https://spec.matrix.org/latest/client-server-api/)
[![CI Build](https://img.shields.io/github/actions/workflow/status/sudo-saad-sad/SGram/build.yml?branch=main&label=CI%20Build&style=for-the-badge)](https://github.com/sudo-saad-sad/SGram/actions)
[![License](https://img.shields.io/badge/License-GPL%20v2%2B-blue.svg?style=for-the-badge)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%206.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/sudo-saad-sad/SGram/releases)

<br/>

[📥 **Download Latest APK (v0.3.0)**](https://github.com/sudo-saad-sad/SGram/releases/download/v0.3.0/SGram-Matrix-debug.apk) • [📦 **All Releases**](https://github.com/sudo-saad-sad/SGram/releases) • [🇮🇷 **توضیحات فارسی**](#-معرفی-پروژه-به-زبان-فارسی-persian) • [🏗️ **Architecture**](#-architecture)

</div>

---

## 💡 What is SGram?

**SGram** bridges the gap between the world's most responsive and beloved mobile messenger UI ([Telegram](https://github.com/Telegram-FOSS-Team/Telegram-FOSS)) and the world's leading open, federated, and decentralized messaging standard ([Matrix](https://matrix.org)).

While Telegram has arguably the best UI, animations, local caching, and gesture system of any mobile app, it relies on centralized proprietary servers and requires a phone number to register. Matrix offers true federation, sovereignty, and privacy, but its mobile clients historically lacked the polish and responsiveness of Telegram.

**SGram replaces the MTProto network backend with a native Matrix Client-Server API v1.11 engine.** You get all of Telegram's legendary speed, fluid gestures, beautiful themes, and local SQLite performance while communicating seamlessly across the Matrix federation.

---

## 🇮🇷 معرفی پروژه به زبان فارسی (Persian)

**اس‌گرام (SGram)** یک کلاینت کاملاً بومی و متن‌باز اندروید است که ظاهر فوق‌العاده روان، انیمیشن‌ها، تم‌ها و عملکرد بی‌نظیر تلگرام را به شبکه آزاد و غیرمتمرکز **ماتریکس (Matrix)** متصل می‌کند.

* **بدون نیاز به شماره تلفن یا سیم‌کارت:** ثبت‌نام و ورود با هر سرور ماتریکس دلخواه (مثل Matrix.org یا سرور اختصاصی خودتان) صرفاً با نام‌کاربری و گذرواژه.
* **۱۰۰٪ رابط کاربری اصلی تلگرام:** تمامی ژست‌های حرکتی، حباب‌های گفتگو، اموجی‌ها، انیمیشن‌های ۱۲۰ هرتز و حافظه پرسرعت SQLite حفظ شده‌اند.
* **چت‌های خصوصی واقعی (1-on-1):** گفتگوهای دونفره مستقیم به عنوان چت شخصی تلگرام با نام، بیو و آواتار طرف مقابل نمایش داده می‌شوند.
* **ساخت خودکار اتاق و دریافت دعوت‌ها:** به محض ارسال اولین پیام به هر آیدی ماتریکس، اتاق چت امن به طور خودکار ساخته شده و پیام‌ها بدون هیچ خطایی ارسال و دریافت می‌شوند.
* **پشتیبانی کامل از پیام‌های صوتی (ویس با Waveform)، تصاویر، ویدیو، واکنش‌های ایموجی، ویرایش و حذف دوطرفه پیام‌ها.**

---

## ✨ Features at a Glance

### 🔐 Authentication & Sovereignty
* **Zero Phone Numbers:** Connect to any Matrix homeserver (e.g. `https://matrix.org`, Mozilla, KDE, or private self-hosted Synapse/Dendrite/Conduit).
* **Multi-Account & Session Persistence:** Credentials and device tokens persist reliably in local storage; zero unexpected logouts or "Deleted Account" errors.

### 💬 Complete Matrix 1.11 & 2.0 Messaging
* **Native 1-on-1 Direct Messaging:** Direct chats map directly to Telegram user dialogs (`TLRPC.TL_peerUser`) with individual profiles and avatars.
* **Auto Direct Room Provisioning:** Sending a message to any Matrix user automatically creates a trusted direct room (`is_direct: true`) without manual invite rituals.
* **Auto-Join Invitations:** Incoming messages and room invites (`rooms.invite`) are automatically accepted and surfaced in your chat list in real-time.
* **Bidirectional Rich Text (HTML):** Full formatting support for **bold**, *italics*, `code`, `pre-formatted blocks`, ~~strikethrough~~, <u>underline</u>, Telegram spoilers, and hyperlinks.
* **Live Message Editing:** Native `m.replace` / `m.new_content` support with instantaneous UI updates and "edited" badges.
* **Message Redactions (Deletions):** Deleting a message in SGram triggers an `m.room.redaction` event, deleting it for everyone and purging it from local storage.
* **Emoji Reactions:** Quick reactions (`m.reaction` / `m.annotation`) rendered natively under message bubbles.
* **Replies & Quoting:** Seamless `m.relates_to` / `m.in_reply_to` handling with message preview bars above the input field.
* **Live Typing Indicators & Read Receipts:** Real-time `m.typing` dots animation and double-check `m.receipt` synchronization.
* **Online Presence:** Real-time presence indicators (`m.presence`) showing user online/offline status and "last seen" timestamps.

### 📸 Rich Media & Voice Notes
* **MSC3916 Authenticated Media:** Fully compatible with Matrix 1.11 authenticated media endpoints with automatic Bearer token injection.
* **MSC3245 Native Voice Notes:** Record and listen to voice notes encoded in OGG/Opus with Telegram's interactive audio waveform visualization.
* **Photos, Videos & Documents:** Send and stream media directly with built-in chunked streaming and local caching.
* **Interactive Location Sharing:** Send and render maps using standard `m.location` (`geo:` URI) format.

### 👥 Room Management & Discovery
* **Matrix User Directory Search:** Search users globally using the homeserver's user directory or directly query `@user:homeserver.org`.
* **MSC2666 Mutual Rooms:** View "Groups in common" directly in any user's profile.
* **Group Administration:** Create private groups, edit room titles (`m.room.name`), update topics (`m.room.topic`), change room photos (`m.room.avatar`), and ban/kick participants.
* **Public Room Directory:** Browse and search public Matrix rooms directly from the app.

---

## 🏗️ Architecture

SGram intercepts Telegram's internal RPC pipeline (`ConnectionsManager`) and redirects all requests to a native Matrix bridge:

```
┌────────────────────────────────────────────────────────┐
│                   Telegram UI Layer                    │
│   (ChatActivity, DialogsActivity, ProfileActivity...)  │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                  MessagesController                    │
│           (Local SQLite Cache & UI State)              │
└───────────────────────────┬────────────────────────────┘
                            │ Intercepts TL_requests
┌───────────────────────────▼────────────────────────────┐
│                    MatrixController                    │
│   • Bi-directional ID Translation (Long <-> Matrix ID) │
│   • Bidirectional HTML Entity Converter               │
│   • Long-polling /sync Engine & Event Dispatcher       │
└───────────────────────────┬────────────────────────────┘
                            │ HTTP REST / JSON
┌───────────────────────────▼────────────────────────────┐
│                      MatrixClient                      │
│   • Matrix Client-Server API v1.11 / Matrix 2.0        │
│   • MSC3916 Authenticated Media Client                 │
│   • MSC2666 Mutual Rooms / MSC3245 Voice Notes         │
└───────────────────────────┬────────────────────────────┘
                            │ HTTPS (Bearer Auth)
┌───────────────────────────▼────────────────────────────┐
│                    Matrix Homeserver                   │
│         (matrix.org, Synapse, Conduit, Dendrite)       │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start & Installation

1. **Download:** Get the latest APK from the [Releases](https://github.com/sudo-saad-sad/SGram/releases) page.
2. **Install:** Open the APK on your Android device (ensure "Install unknown apps" is enabled).
3. **Log In:**
   * **Homeserver:** Enter your homeserver URL (e.g. `https://matrix.org`).
   * **Username:** Enter your Matrix username (e.g. `alice` or `@alice:matrix.org`).
   * **Password:** Enter your account password.
4. **Chat:** Search for any Matrix user or room using the search bar at the top, or start a new direct chat!

---

## 🛠️ Building from Source

SGram builds using standard Android Gradle tooling. Because Telegram's native C++ codebase is massive, the CI workflow dynamically injects prebuilt native libraries and compiles the application on GitHub Actions:

```bash
# Clone the repository
git clone https://github.com/sudo-saad-sad/SGram.git
cd SGram

# Create dummy API keys (Matrix backend does not use Telegram API credentials)
echo "APP_ID=12345" > API_KEYS
echo "APP_HASH=0123456789abcdef0123456789abcdef" >> API_KEYS

# Build Debug APK
./gradlew assembleAfatDebug
```

> [!TIP]
> Automated APK builds are built on every commit to `main` via GitHub Actions (Ubuntu 16GB runner) and published to [Releases](https://github.com/sudo-saad-sad/SGram/releases).

---

## 🛡️ Privacy & Freedom

* **No proprietary Google Play Services dependencies:** Completely FOSS base derived from Telegram-FOSS.
* **No telemetry, analytics, or crash reporters:** No third-party trackers.
* **Decentralized:** Your conversations reside on the Matrix homeserver of your choice, not on proprietary servers.

---

## 📜 License & Credits

* SGram is licensed under the **GNU General Public License v2.0 or later** ([GPLv2+](LICENSE)).
* Based on [Telegram-FOSS](https://github.com/Telegram-FOSS-Team/Telegram-FOSS) and the original [Telegram for Android](https://github.com/DrKLO/Telegram).
* Built for the [Matrix.org](https://matrix.org) decentralized communication ecosystem.
