# 📋 SAM BOND — Attendance App

An Android attendance app for **SAM BOND**: selfie + live GPS check-in, late-arrival detection, leave requests with yearly balances, overnight **Night Hold** reporting, and a multi-level manager hierarchy with approvals — all backed by a **Google Sheet**. No server or database to run, and APKs are built automatically by **GitHub Actions**.

> Android 7.0+ · Kotlin · Google Apps Script backend · Pay period **26th → 25th** · Timezone **Asia/Dhaka**

---

## ✨ Features

### For every employee
- **Login / Logout / Change password** — usernames live in a Google Sheet and passwords are stored there only as salted hashes. The app never keeps the password: it signs in once and uses an expiring token. Staff change their own password in the app (current password required, at least 8 characters). Five wrong passwords pause an account for 15 minutes. The login screen has **Keep me signed in on this device** (ticked by default): ticked, you stay signed in for 30 days and the sign-in renews itself every time you use the app; unticked, the sign-in ends after 12 hours (use this on a shared phone).
- **Selfie attendance** — front-camera selfie (required, JPEG only, size-limited), live GPS location (fake/mock GPS is rejected), address lookup, and a **server-side** date/time stamp (changing the phone's clock does nothing).
- **Location is required** — a submission without a real GPS location, or with one outside Bangladesh, is rejected by the server, not just the app.
- **Late Present** — there is a **10-minute grace period**: submitting up to 9:09 AM is on time, and at or after **9:10 AM** is marked *⏰ Late Present* with the time.
- **Absent** — still unmarked after **6:00 PM** counts as *Absent*; past unmarked days show as Absent too. **Friday is an automatic weekly off**, and so is any **custom holiday** an admin adds; neither is ever counted as absent.
- **Today's selfie on Home** — once you've checked in, the *Today's Status* card shows your selfie and where it was taken.
- **One check-in per day** — a second submission on the same day is blocked.
- **Leave requests** — Casual, Sick or Earn leave with a from/to date picker; days are counted automatically.
- **Leave balance** — **Casual 10 · Sick 14 · Earn 17** days per year, with *left of total · used* shown per type.
- **Attendance ↔ leave conflict checks** — you can't mark attendance on an approved-leave day, or apply for leave on a day you already marked present.
- **Night Hold** — staying away overnight? Report it with:
  - live GPS (fake/mock locations are rejected),
  - **district → thana** pickers (all **64 districts**, with searchable lists),
  - **hotel name**,
  - **one per day**; the form can't be submitted without real data (checked again on the server).
- **Notices** — a notice from an admin appears as a card on Home (tap to see them all).
- **Calendar** — the pay period (26th–25th) at a glance: present, late, leave, Friday off, 🎉 holidays, absent, and 🌙 night-hold days. Tap any date for details, including that day's night-hold status.

### For managers
- **Unlimited levels** — everything uses one *Manager* column, so Employee → ZM/ASM → RSM/DSM → SM → Operation Manager (or any structure) works with no hardcoded roles.
- **My Team** — each direct report's status today (Present / Late / On Leave / Absent / Off / Pending), their selfie and location, and a live roll-up for any report who manages a team.
- **Drill down** — tap any manager to see their team, all the way to individual employees.
- **Approve / Reject leave** — only for your **own direct reports**; higher-level views are read-only, so approvals always follow the chain of command.

### For admins
An **admin** is any user whose **Role** cell in the Users sheet says `admin` (see *Make your first admin* below). Admins get an **Admin Panel** in the 👤 menu on Home with three tabs:
- **👥 Users** — **add a new user** (username, name, manager, optional admin role) and **reset anyone's password**. You can type a temporary password or leave it blank and the server makes one (shown once, with a Copy button). The person is signed out everywhere and must choose their own password at their next sign-in. Creating a user or resetting a password asks for **your own password** again.
- **📢 Notices** — post a title + message to everyone (shown for 7 days by default, up to 90) and remove notices.
- **🎉 Holidays** — pick a date and a name; that day behaves like Friday for everyone (nobody is absent, the team view shows *Off*, Calendar and Home show the holiday name). Remove a holiday and the day counts as a normal working day again.

Notices appear when a phone opens or refreshes Home. They are **not push notifications** (no sound or pop-up while the app is closed); that would need Firebase.

---

## 📱 Screens

| Screen | What it does |
|---|---|
| **Home** | Fixed header with clock, today's status, leave-balance bars, and this month's night holds |
| **Attendance** | Selfie + location capture and submit |
| **Leave** | Apply for leave, see balances and request history |
| **Team** *(managers only)* | Team status, roll-ups, drill-down, leave approvals |
| **Calendar** | Pay-period view of attendance, leave and night holds |
| **Night Hold** | Overnight stay report with location, district/thana and hotel |
| **Notices** | Everything admins have posted |
| **Admin Panel** *(admins only)* | Add users, reset passwords, post notices, add holidays |

A **bottom bar** (Home · Attendance · Leave · Team · Calendar · Night Hold) is on every screen, and every screen has a compact header that stays fixed while the content scrolls. ⟳ buttons refresh any screen, and the 👤 menu on Home has *Change Password*, *Admin Panel* (admins only), *Logout* and the app version.

---

## 🛠 Tech

| Part | Technology |
|---|---|
| App | Kotlin, AppCompat, Material Components |
| Camera | CameraX |
| Location | Google Play Services (fused location) |
| Backend | Google Apps Script web app (`Code.gs`) |
| Data | Google Sheets (+ Google Drive for selfies) |
| Build | GitHub Actions → signed release APK |

Speed: screens open instantly from a saved copy of the last response and then refresh; selfies are downloaded once and cached; uploads are shrunk before sending.

---

## 🚀 Setup

### 1. Google Sheet + Apps Script
1. Create a new Google Sheet.
2. **Extensions → Apps Script**, paste in `Code.gs`, and save.
3. If the script is **not** opened from inside the Sheet, set `SHEET_ID` at the top of `Code.gs` to the ID in the sheet's URL (the text between `/d/` and `/edit`).
4. Run the **`setup`** function once and approve the permission prompts. It creates:
   - **Users** — `Username | Password | Name | Manager | TokenEpoch | Role | MustChange` (*Manager* = another user's username, blank if none; this builds the hierarchy. *Password* holds a hash after the first sign-in. *Role* is `admin` for admins and blank for everyone else. *TokenEpoch* and *MustChange* are managed by the script, leave them alone)
   - **Attendance** — filled in as staff check in
   - **Leaves** — filled in as staff apply; the **Status** column (Pending / Approved / Rejected) is set by a manager in the app, or by hand
   - **NightHold**, **Notices** and **Holidays** — created automatically on first use (admins add notices and holidays from the app; you can also read them in the Sheet)
5. Add your staff to **Users** and link the **Manager** column. You can type a plain password for a new person; it is replaced by a hash the first time they sign in.
   - **Upgrading an existing sheet?** Make a backup copy of the Sheet, then run **`upgradeSecurity`** once. It hashes every plain-text password and creates the token secret. It is safe to run again. Delete the backup afterwards, and remember that Google Sheets *Version history* still holds the old plain-text values.
6. **Make your first admin:** open `Code.gs`, find `function makeAdmin()`, change `'manager1'` to your own username, select `makeAdmin` and press **Run** (or type `admin` into the *Role* cell yourself). Only someone who can edit the Sheet can do this, so an admin can't be created from the app by an ordinary user. After that, admins add other users from the app.
7. **Deploy → New deployment → Web app** — *Execute as* **Me**, *Who has access* **Anyone**. Copy the URL ending in `/exec`.

### 2. Connect the app
Open `app/src/main/java/com/example/attendance/Api.kt`, paste the URL into `Config.SCRIPT_URL`, and commit.

### 3. Signing key (one-time)
Releases are signed with a private key that is **never** stored in the repo.
1. On a computer with Java, create the key (keep the file and both passwords somewhere safe — **if you lose it you can never update the app again**, everyone would have to uninstall and reinstall):
   ```
   keytool -genkeypair -v -keystore sambond-release.jks -alias sambond -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Turn it into text: `base64 -w0 sambond-release.jks` (Linux), `base64 -i sambond-release.jks | tr -d '\n'` (macOS), or in PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("sambond-release.jks"))`.
3. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add four secrets: `KEYSTORE_BASE64` (the text from step 2), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`sambond` above) and `KEY_PASSWORD`.

### 4. Build the APK
- Push to the repo or run **Actions → Build APK → Run workflow**.
- Download the APK from the run's **`app-release`** artifact, or from the repo's **Releases** page (easiest on a phone).
- Install it on an Android phone (Android 7.0+).

---

## 🔢 Versions & updates

- Every build is numbered **`1.0.<run number>`** (for example `1.0.56`). The APK is named `SAM-BOND-Attendance-v1.0.56.apk` and is also published on the **Releases** page.
- The version shows on the login screen and in the 👤 menu on Home.
- All builds are signed with **one private key** kept in GitHub Secrets (see *Signing key*), and each build has a higher version number than the last — so a new APK installs **over** the old one as a normal update.
- ⚠️ **One-time step:** an APK signed with an older key (the previous public debug key) can't be updated in place. Android shows *"Conflicting app signatures"*. Uninstall the old app once, then install the new one. After that, updates just work. Your data is safe — it all lives in the Google Sheet.
- If the four signing secrets are set, every APK is signed with your key. If they are **missing**, the build still runs but with a throw-away key: the file is named `…-TEMP-KEY.apk`, the run page shows a ⚠️ warning, and it can only be installed fresh (uninstall the old app first). If a secret is wrong the build **fails with a clear message**.

### Updating from a phone (no computer)
1. Upload an `update.zip` to the repo's main page (**Add file → Upload files**). The **Apply Update** workflow unpacks it into the project automatically.
2. Run **Actions → Build APK → Run workflow**.
3. Download the new APK.

Workflow files (`.github/workflows/*.yml`) can't be changed by a zip — edit those directly on GitHub.

---

## ⚙️ Configuration

| Setting | Where | Default |
|---|---|---|
| Sheet ID | `Code.gs` → `SHEET_ID` | your sheet |
| Timezone | `Code.gs` → `TZ` | `Asia/Dhaka` |
| Leave quotas | `Code.gs` → `QUOTA` | Casual 10, Sick 14, Earn 17 (days/year) |
| Late cutoff | `Code.gs` → `LATE_HOUR`, `LATE_GRACE_MINUTES` | `9` and `10` (late from 9:10 AM) |
| Absent cutoff | `Code.gs` → `ABSENT_AFTER_HOUR` | `18` (6:00 PM) |
| Pay period | `Code.gs` → `PERIOD_START_DAY` / `PERIOD_END_DAY` | 26 → 25 |
| Weekly off | `Code.gs` → `isFridayDate` | Friday |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |
| Short sign-in length (box unticked) | `Code.gs` → `SHORT_SESSION_HOURS` | 12 hours |
| Minimum password length, lockout, token lifetime | `Code.gs` → `MIN_PASSWORD_LENGTH`, `MAX_FAILED_LOGINS`, `LOCKOUT_MINUTES`, `TOKEN_DAYS` | 8, 5 tries, 15 min, 30 days |
| Notice length of stay | `Code.gs` → `NOTICE_DEFAULT_DAYS`, `NOTICE_MAX_DAYS` | 7 days, 90 days |
| Allowed check-in area | `Code.gs` → `GEO_CHECK`, `GEO_BOX` | Bangladesh + margin |
| Districts & thanas | `app/src/main/assets/bd_locations.txt` | 64 districts |

> **After any change to `Code.gs`:** saving isn't enough. Go to **Deploy → Manage deployments → ✏️ → Version: New version → Deploy**, or the live URL keeps running the old code.

The late/absent hours and pay period are also used by the app screens (Home and Calendar) — keep them in sync with `Code.gs` if you change them.

---

## 🧩 Troubleshooting

| Problem | Fix |
|---|---|
| *"Network or server error"* in the app | Check the phone's internet and that `Config.SCRIPT_URL` is the latest `/exec` URL. |
| App shows old behaviour after editing `Code.gs` | Deploy a **New version** (see above). |
| *"Conflicting app signatures"* when installing | Uninstall the old app once (see **Versions & updates**). |
| *"Too many wrong passwords"* | Wait the 15 minutes it shows; the pause ends by itself. |
| *"Your session ended"* | Normal after a password change on another phone, a logout, or 30 days of not opening the app. Sign in again. |
| APK is named `…-TEMP-KEY.apk` or won't update the installed app | The signing secrets are missing. Add the four secrets (see **Signing key**), uninstall the old app once, install the new one. |
| Someone forgot their password | Admin Panel → Users → **Reset password** (this also lifts a *too many wrong passwords* pause). |
| The Admin Panel isn't in the 👤 menu | Their *Role* cell must say `admin`; then open or refresh Home once. |
| Someone lost their phone | In the Apps Script editor run `revokeOneUser` (edit the username inside it first), or `revokeEveryone`. |
| Build APK fails | Open the failed run's **Summary** — the *"Build failed – the real error"* box shows the exact line. The full log is saved as a `build-log` artifact. |
| Nothing is written to the Sheet | Make sure the script is bound to the Sheet or `SHEET_ID` is set, and that `setup` was run once. |

---

## 🔒 Security notes

- **Passwords** are stored only as salted PBKDF2-SHA256 hashes. The app never saves your password; it keeps a signed token (encrypted with the Android Keystore) that expires after 30 days, is renewed while you keep using the app, and stops working the moment you change your password, log out, or an admin revokes it.
- **Brute-force protection:** 5 wrong passwords pause that account for 15 minutes. Apps Script cannot see a caller's IP address, so this is per account, not per attacker; an attacker can pause someone's account, but cannot get into it.
- **Selfies and locations are validated on the server:** photo required, JPEG only, size-limited; coordinates must be real and inside the allowed area; the phone's "fake GPS" flag is rejected. A phone with a modified app or a rooted device can still lie about its GPS — only Play Integrity attestation would close that, and it is not included.
- **Spreadsheet formula injection** is blocked: text typed on a phone (address, hotel, leave reason) is always stored as text.
- **Admin actions** are checked on the server on every request (the *Role* cell is read each time, so removing `admin` takes effect at once), and adding a user or resetting a password needs the admin's own password again. A password an admin sets is temporary: the person must choose their own at first sign-in. Only someone who can edit the Sheet can create the first admin.
- **Authorization** is enforced on the server: selfies are visible only to the person and their managers; leave can be decided only by a direct manager; team views only down your own chain.
- **Backups are disabled** for the app, and saved data on the phone is encrypted and wiped at logout.
- The script URL is public (anyone who has the APK has it), so assume anyone can call it. Everything above is built with that in mind.
- Attendance time is stamped by Google's server. Leave balances reset each calendar year and count **Approved + Pending** requests. A manager is anyone who appears in another user's **Manager** column.
- Keep the repository **private** or limit who has write access: anyone who can push can change the app, and the *Apply Update* workflow applies uploaded zips automatically.

---

## 📁 Project layout

```
.github/workflows/
  build.yml            Builds the APK, numbers it, publishes it
  apply-update.yml     Unpacks an uploaded update.zip into the project
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    assets/bd_locations.txt     64 districts → thanas
    java/com/example/attendance/
      Api.kt            Server URL, session (token), API call helper
      Security.kt       Encrypted storage, wipe-on-logout, fake-GPS check
      Cache.kt          Saved-response cache for instant screens
      Admin.kt          Admin flag (the server decides who is an admin)
      AdminActivity.kt  Admin Panel: users, notices, holidays
      NoticesActivity.kt  List of notices
      Ui.kt             Shared header + bottom bar
      MainActivity.kt           Home
      AttendanceActivity.kt     Selfie + location check-in
      LeaveActivity.kt          Leave + balances
      NightHoldActivity.kt      Night hold
      CalendarActivity.kt       Calendar
      TeamActivity.kt           Manager team view + approvals
      …                         Login, photos, pickers, dialogs
    res/                Layouts, colors, themes, drawables
```

---

## 📄 License

Personal / internal use project. Adjust and reuse as needed.
