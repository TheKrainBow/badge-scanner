# 42 Badge Scanner

Android app (Kotlin + Jetpack Compose) that reads a student badge over NFC,
computes the Wiegand code from the badge UID (same math as
`EXAMPLES/nfc_mifare2wiegand.py`), resolves the student on the access control
system ("CA", the same API 42-watchdog uses), then fetches the login and
profile picture from the 42 intranet.

## What happens on a scan

1. **NFC read** — the tag UID is read in reader mode (MIFARE Classic, NfcA…).
2. **Wiegand conversion** — port of `nfc_mifare2wiegand.py`: the UID as read
   (big-endian, e.g. `E01CBEDB`) is the script's input, giving the badge hex,
   the facility code (`u2`), the card number (`u1 u0`), the Wiegand-26 code
   (`FC` + 5-digit card number), the unpadded "IXOFF" variant and the
   byte-reversed "Premium" number. Verified against the CA with a real badge:
   `E01CBEDB` → `19007392`.
3. **CA lookup** — `GET {caEndpoint}/users/{badge}` with basic auth, exactly
   like 42-watchdog's live-attendance (`CreateNewUser`). The W26 code is tried
   first, then the unpadded variant, then the Premium number; the first hit
   wins. `properties.ft_login` / `properties.ft_id` are extracted, matching
   watchdog's `UserResponse`. The CA's self-signed TLS certificate (Ixoff
   ibox4, CN `ibox4.ibox.pro`) is pinned in `res/raw/ca_cert.pem` — it expires
   in Oct 2029; re-extract it with
   `openssl s_client -connect ca.42nice.fr:443 -showcerts` if it changes.
4. **Intranet lookup** — a `client_credentials` token is requested on
   `https://api.intra.42.fr/oauth/token`, then `GET /v2/users/{ft_id|ft_login}`
   returns the canonical login and the profile picture
   (`image.versions.medium`).
5. **History** — every scan (including failures) is stored locally with
   login, photo URL, badge hex and Wiegand code, newest first (capped at 500).

## Screens

- **Scan** — live status; on success shows the photo, login and all computed
  badge codes.
- **History** — the latest scans with photo, login, hex, wiegand and time;
  trash icon clears it.
- **Settings** — CA endpoint/username/password and 42 API UID/secret
  (defaults point to `https://ca.42nice.fr/api` and `api.intra.42.fr`).
  Stored on-device only, in the app's private DataStore.

## Building

Requirements: JDK 17+, Android SDK (platform 35). With Android Studio just
open the project folder. From the command line:

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # runs the Wiegand conversion tests
```

Install on a phone with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- Minimum Android 8.0 (API 26); the device must have NFC.
- 7-byte UIDs are truncated to the first 4 bytes, which is what Wiegand
  readers put on the bus.
- The Wiegand port is locked in by unit tests generated from the original
  Python script (`app/src/test/.../WiegandTest.kt`).
- If your CA stores badge ids in another format, the candidates tried are
  visible in the scan error message ("No CA user for badge (tried …)"), which
  makes it easy to see what to adjust in `BadgeCodes.caCandidates`.
