# PhishGuard: live demo script (about 6 minutes)

## Before the audience arrives
- [ ] Backend running: `py -m uvicorn app:app --app-dir backend --host 0.0.0.0 --port 8000`
- [ ] `http://127.0.0.1:8000/health` returns `"status":"ok"`
- [ ] Emulator booted and PhishGuard installed (`adb devices` shows `device`)
- [ ] App opened once already, so the first check isn't slowed by a cold start
- [ ] Backup: `docs/shots/*.png` open in a folder in case anything fails live

## 1. The problem (30 s)
> "On a phone, links arrive in SMS and chat apps, and the address is often cut
> off. Can you tell whether `paypal.com.secure-login.gq` is PayPal? PhishGuard
> checks a link before you open it, and tells you **why**."

## 2. A trusted site (45 s)
Type `google.com` → **Check this link**.
- Point out **CLEAR**, the green stripe, and **SOURCE ALLOWLIST · 0 MS**.
> "Well-known sites are answered instantly from an allowlist. The model isn't
> even called."

## 3. A phishing link (90 s)
Tap **Check another link**, then type `paypal.com.secure-login.gq/account/verify`.
- **MALICIOUS**, score 0.998, red stripe.
- **"Appears to imitate Paypal"**: the brand name is in the address, but the
  real domain is `secure-login.gq`.
- Walk through the **why-bars**: "Suspicious domain ending" is longest, then
  "Brand name appears but isn't the real site".
> "These bars are SHAP values: how much each feature pushed this URL's score
> toward phishing."

## 4. A borderline link (45 s)
Check `login.account-check.net/verify`, which shows **CAUTION** with a score of 0.753.
> "It's just under the blocking threshold of 0.758, so we warn rather than block.
> That middle band keeps false alarms from becoming hard blocks."

## 5. Share from another app (60 s)
Run this from a terminal, or share from Chrome or Messages on the emulator:
```
adb shell am start -a android.intent.action.SEND --es android.intent.extra.TEXT "http://paypal.com.secure-login.gq/x" -t text/plain com.phishguard.app/.MainActivity
```
- The app opens and checks the link without any typing, and shows **MALICIOUS**.
> "In real use you long-press a link in any app → Share → 'Check this link'."

## 6. Error state (optional, 30 s)
Stop the backend (Ctrl+C) and check any link to show the clear
"Can't reach the PhishGuard server" box with **Retry**. Restart the backend afterwards.

## 7. The numbers (60 s), using a slide or `docs/REPORT.md` §5
- 411,244 URLs; test set of 82,249 never used in training.
- ROC-AUC **0.9629**. At the default threshold: precision **0.827**, recall
  **0.751**, false-positive rate **3.0%**.
- Scoring takes **~2 ms** per URL; a full check with explanation takes ~19–36 ms.
- Be upfront: "The dataset is 16% phishing, far more than real traffic, so real
  precision would be lower. That's why our threshold note recommends a stricter
  0.85 block threshold for deployment."

## If something goes wrong
| Symptom | Fix |
|---|---|
| "Can't reach server" | Backend not running, or wrong port. Restart uvicorn on port 8000 |
| Stuck on the splash screen | First cold start; wait a few seconds |
| Physical phone can't connect | Needs the PC's LAN IP in `gradle.properties` and `network_security_config.xml`, the same Wi-Fi network, and the firewall open on port 8000 |
