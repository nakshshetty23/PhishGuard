# Testing the guarded browser (Phase 5)

## Pointing the WebView at the camera test page
`web/camera_test.html` is bundled into the app's assets by `app/build.gradle.kts`
(`assets.srcDir(<repo>/web)`) and served by `WebViewAssetLoader` at:

    https://appassets.androidplatform.net/assets/camera_test.html

(a secure origin, so `getUserMedia` exists). Either:
- open the **BROWSE** tab and tap **TEST PAGE** (debug builds only), or
- type `appassets.androidplatform.net/assets/camera_test.html` in the address bar.

The host is treated as local and never sent to `/check`.

## Expected results
| Action | Result |
|---|---|
| Browse `paypal.com.secure-login.gq/account/verify` | "PAGE BLOCKED — NOT LOADED" + MALICIOUS card with why-bars; page never loads |
| Browse `google.com` | Loads normally (in-app allowlist, no server call) |
| Open the test page | Page loads, a PAGE REPORT appears: CAUTION with factor **"Requests camera on load"**; red strip "Blocked a camera request…" from the native prompt |
| Backend down, unknown domain | "COULD NOT VERIFY THIS LINK" with Retry / Load anyway / Cancel |

Backend must be running (`uvicorn app:app --app-dir backend --host 0.0.0.0 --port 8000`).
Debug builds enable `chrome://inspect` for the WebView.

## Design notes
- `shouldOverrideUrlLoading` can't suspend, so it returns `true` (cancel), the ViewModel
  checks asynchronously, and only an approved URL is passed to `loadUrl`.
  `onPageStarted` re-checks navigations that skip the first hook (back/forward, form posts).
- Verdicts are cached **per domain** for the session. A safe verdict for a host therefore
  covers every path on it; clear the cache by restarting the app.
- The JS bridge is exposed to the page, so it accepts only a fixed `kind` string and a boolean.
- Camera/mic is deny-by-default; the app never declares the CAMERA permission.
