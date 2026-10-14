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
- **Today's selfie on Home** — once you've checked in, the *Today's Status* circle shows your selfie as a small round picture (tap it to see it bigger) with where it was taken underneath.
- **One check-in per day** — a second submission on the same day is blocked.
- **Leave requests** — Casual, Sick, Earn or Paternity leave with a from/to date picker; days are counted automatically.
- **Leave application picture + reason (both required)** — attach a photo of your signed leave application (📷 *Take photo* or 🖼 *Choose picture*) **and** write a reason (3+ characters). A request can't be sent without both; the server refuses it too. The picture is shrunk on the phone and stored privately in the Drive folder with the selfies; you, your manager and every supervisor above you can open it from the leave request (*📎 View leave application*).
- **Leave balance** — **Casual 10 · Sick 14 · Earn 17 · Paternity 3** days per year, with *left of total · used* shown per type. (The app has no gender field, so every employee gets the Paternity balance.)
- **Attendance ↔ leave conflict checks** — you can't mark attendance on an approved-leave day, or apply for leave on a day you already marked present.
- **Night Hold** — staying away overnight? Report it with:
  - live GPS (fake/mock locations are rejected),
  - **district → thana** pickers (all **64 districts**, with searchable lists),
  - **hotel name**,
  - **only between 9:00 PM and 6:00 AM** — outside those hours the Night Hold screen shows a *closed* notice and the server refuses a submission. It is **one per night**: a night is named by the date its evening started, so a 2:00 AM submission counts for the night before. The form can't be submitted without real data (checked again on the server).
- **Notices** — a notice from an admin appears as a card on Home, behind a 🔔 **bell** at the top (a red number shows how many you haven't opened), and as a **pop-up on Home every time you sign in** or open the app fresh. *Got it* closes it; *View all notices* opens the list.
- **Update from inside the app** — when an admin publishes a newer version, a blue **⬆️ Update available** card appears on Home (and an *Update app* item in the 👤 menu). Tap **Update**, the app downloads the new version (with a progress bar), checks it isn't damaged, and opens Android's installer. The first time, Android asks you to allow *Install from this source* for this app. Your data is not affected.
- **My Profile** — open it from the round photo at the top-left of Home, or from the 👤 menu → *My Profile*. You can:
  - **change your profile photo** (📷 *Take photo* or 🖼 *Choose picture*) — this is separate from your daily attendance selfie, and it replaces the letter in the round avatar on Home;
  - keep your **official phone**, **personal phone** and **email address**;
  - set an **emergency contact**: phone number plus **relation** from a drop-down (*Wife, Son, Brother, Mother, Father, Daughter, Other*). Choose **Other** and a box appears where you write the relation yourself;
  - add your **bike number**, and — **for managers only** — your **laptop model**.
  
  Every field is optional (an emergency phone and its relation go together). Phone numbers are checked (digits, 8–15, optional `+`) and email addresses too. Only you can change your own profile.
- **Calendar** — the pay period (26th–25th) at a glance: present, late, leave, Friday off, 🎉 holidays, absent, and 🌙 night-hold days. Tap any date for details, including that day's night-hold status.

### For managers
- **Unlimited levels** — everything uses one *Manager* column, so Employee → ZM/ASM → RSM/DSM → SM → Operation Manager (or any structure) works with no hardcoded roles.
- **My Team** — each direct report's status today (Present / Late / On Leave / Absent / Off / Pending), their selfie and location, and a live roll-up for any report who manages a team.
- **Drill down** — tap any manager to see their team, all the way to individual employees.
- **Approve / Reject leave** — a supervisor can decide leave for **everyone below them**, direct reports and people further down the chain. Your Team screen lists all of their requests (tap a sub-manager to see just that team). Nobody can approve their own leave, and people outside your chain are refused by the server.

### For admins
An **admin** is any user whose **Role** cell in the Users sheet says `admin` (see *Make your first admin* below). Admins get an **Admin Panel** in the 👤 menu on Home with four tabs:
- **👥 Users** — a **search box** finds people by name, username or manager. **Add a new user** (username, name, manager, optional admin role) and **reset anyone's password**. You can type a temporary password or leave it blank and the server makes one (shown once, with a Copy button). The person is signed out everywhere and must choose their own password at their next sign-in. Creating a user or resetting a password asks for **your own password** again.
  Each user card shows the **app version installed on their phone** (e.g. *📱 v1.0.71 · seen 14 Oct*): green when it is the latest published version, orange *⬆ needs update* when it is older, and grey until that person has opened the new app once (the phone reports its version whenever Home loads; the sheet records it in Users columns H–J, *AppVersion / AppCode / LastSeen*, and writes only when something changed). Every user card also has **📅 Calendar** — that person's pay-period calendar (read-only): each day's attendance with **time and location**, late days, leave, holidays, and night holds with **time, hotel and location**. Tap a date for details, and tap *🗺 …on map* to open the place in Maps. The admin sees *when and where*, but not selfies or leave-application pictures. And a **👤 Profile** button: the admin sees and can change that person's photo, phones, email, emergency contact and relation, bike number and laptop model (the laptop field follows the same managers-only rule). Changing a profile does not ask for the admin's password. Admins can see everyone's *profile photo*, but not other people's selfies or leave applications unless they are above them in the Manager chain.
- **📢 Notices** — post a title + message to everyone (shown for 7 days by default, up to 90) and remove notices.
- **🎉 Holiday** — pick a date and a name; that day behaves like Friday for everyone (nobody is absent, the team view shows *Off*, Calendar and Home show the holiday name). Remove a holiday and the day counts as a normal working day again.
- **📲 App** — **publish a new app version for everyone.** Download the new APK from GitHub onto your phone, tap *Upload new version (APK)*, pick the file, add a short *What's new* note, confirm with your own password, and wait for the progress bar. The app reads the version out of the APK itself and refuses a file that isn't this app. Everyone signed in is then offered the update on Home. *Stop offering this update* withdraws it.

The notice pop-up is shown by the app when it is open. It is **not a phone push notification** (no sound or banner while the app is closed); that would need Firebase.

---

## 📱 Screens

| Screen | What it does |
|---|---|
| **Home** | Fixed header with clock and a 🔔 notice bell, today's status (with your round selfie), notices, leave-balance bars, and this month's night holds |
| **Attendance** | Selfie + location capture and submit |
| **Leave** | Apply for leave (reason + application picture), see balances and request history |
| **Team** *(managers only)* | Team status, roll-ups, drill-down, leave approvals |
| **Calendar** | Pay-period view of attendance, leave and night holds |
| **Night Hold** | Overnight stay report with location, district/thana and hotel |
| **My Profile** | Change your photo; phones, email, emergency contact + relation, bike number, laptop model (managers) |
| **Notices** | Everything admins have posted |
| **Admin Panel** *(admins only)* | Add users, reset passwords, post notices, add holidays, publish app updates |

A **bottom bar** (Home · Attendance · Leave · Team · Calendar · Night Hold) is on every screen, and every screen has a compact header that stays fixed while the content scrolls. ⟳ buttons refresh any screen, and the 👤 menu on Home has *My Profile*, *Change Password*, *Admin Panel* (admins only), *Logout* and the app version.

---

## 🛠 Tech

| Part | Technology |
|---|---|
| App | Kotlin, AppCompat, Material Components |
| Camera | CameraX |
| Location | Google Play Services (fused location) |
| Backend | Google Apps Script web app (`Code.gs`) |
| Data | Google Sheets (+ Google Drive for selfies, leave-application pictures and the published app update) |
| Build | GitHub Actions → signed release APK |

Speed: screens open instantly from a saved copy of the last response and then refresh; selfies are downloaded once and cached; selfies and leave-application pictures are shrunk before sending.

---

## 🚀 Setup

### 1. Google Sheet + Apps Script
1. Create a new Google Sheet.
2. **Extensions → Apps Script**, paste in `Code.gs`, and save.
3. If the script is **not** opened from inside the Sheet, set `SHEET_ID` at the top of `Code.gs` to the ID in the sheet's URL (the text between `/d/` and `/edit`).
4. Run the **`setup`** function once and approve the permission prompts. It creates:
   - **Users** — `Username | Password | Name | Manager | TokenEpoch | Role | MustChange` (*Manager* = another user's username, blank if none; this builds the hierarchy. *Password* holds a hash after the first sign-in. *Role* is `admin` for admins and blank for everyone else. *TokenEpoch* and *MustChange* are managed by the script, leave them alone)
   - **Attendance** — filled in as staff check in
   - **Leaves** — filled in as staff apply; the **Status** column (Pending / Approved / Rejected) is set by a manager in the app, or by hand. The **Application** column (added automatically) holds the Drive file id of the attached leave-application picture
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
- Download the APK from the run's **`app-release`** artifact (a zip containing the APK), or from the repo's **Releases** page (easiest on a phone). Builds signed with your own key are published as normal releases; temporary-key builds are saved as a **draft** release that only you can see, because repositories with *immutable releases* refuse files on a pre-release.
- Install it on an Android phone (Android 7.0+).

---

## 🔢 Versions & updates

- Every build is numbered **`1.0.<run number>`** (for example `1.0.56`). The APK is named `SAM-BOND-Attendance-v1.0.56.apk` and is also published on the **Releases** page.
- The version shows on the login screen and in the 👤 menu on Home.
- All builds are signed with **one private key** kept in GitHub Secrets (see *Signing key*), and each build has a higher version number than the last — so a new APK installs **over** the old one as a normal update.
- ⚠️ **One-time step:** an APK signed with an older key (the previous public debug key) can't be updated in place. Android shows *"Conflicting app signatures"*. Uninstall the old app once, then install the new one. After that, updates just work. Your data is safe — it all lives in the Google Sheet.
- If the four signing secrets are set, every APK is signed with your key. If they are **missing**, the build still runs but with a throw-away key: the file is named `…-TEMP-KEY.apk`, the run page shows a ⚠️ warning, and it can only be installed fresh (uninstall the old app first). If a secret is wrong the build **fails with a clear message**.

### Updating the app for everyone (in-app update)
1. Build the new APK (**Actions → Build APK → Run workflow**) and download it to your phone from the **Releases** page.
2. In the app: 👤 menu → **Admin Panel → 📲 App → Upload new version (APK)**, pick the downloaded file, confirm.
3. Everyone who opens the app sees **⬆️ Update available** on Home and installs it in two taps.

Requirements and limits:
- The APK must be signed with **the same key** as the installed app (the four signing secrets). Android refuses anything else, so a wrong or tampered file can never replace the app. A `…-TEMP-KEY.apk` cannot update a normally-signed app.
- The update only reaches phones that already run a build **with this feature**. A phone with an older build must install the new APK once by hand; after that, updates come through the app.
- Each phone needs the Android setting **Install unknown apps → allow for SAM BOND Attendance** (the app opens that screen the first time).
- The file is stored in Drive (folder `AppUpdates`) in 512 KB pieces; only signed-in users can download it. Uploading a newer version deletes the older file. The biggest APK accepted is 40 MB.

### Updating the project from a phone (no computer)
1. Upload an `update.zip` to the repo's main page (**Add file → Upload files**). The **Apply Update** workflow unpacks it into the project automatically.
2. Run **Actions → Build APK → Run workflow**.
3. Download the new APK.

Workflow files (`.github/workflows/*.yml`) can't be changed by a zip — edit those directly on GitHub (paste the new `build.yml` over the old one). Backend changes (`Code.gs`) are not part of the zip either: paste them into Apps Script and deploy a **New version**.

---

## ⚙️ Configuration

| Setting | Where | Default |
|---|---|---|
| Sheet ID | `Code.gs` → `SHEET_ID` | your sheet |
| Timezone | `Code.gs` → `TZ` | `Asia/Dhaka` |
| Leave quotas | `Code.gs` → `QUOTA` | Casual 10, Sick 14, Earn 17, Paternity 3 (days/year) |
| Late cutoff | `Code.gs` → `LATE_HOUR`, `LATE_GRACE_MINUTES` | `9` and `10` (late from 9:10 AM) |
| Absent cutoff | `Code.gs` → `ABSENT_AFTER_HOUR` | `18` (6:00 PM) |
| Pay period | `Code.gs` → `PERIOD_START_DAY` / `PERIOD_END_DAY` | 26 → 25 |
| Weekly off | `Code.gs` → `isFridayDate` | Friday |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |
| Short sign-in length (box unticked) | `Code.gs` → `SHORT_SESSION_HOURS` | 12 hours |
| Minimum password length, lockout, token lifetime | `Code.gs` → `MIN_PASSWORD_LENGTH`, `MAX_FAILED_LOGINS`, `LOCKOUT_MINUTES`, `TOKEN_DAYS` | 8, 5 tries, 15 min, 30 days |
| Shortest reason for a leave request | `Code.gs` → `MIN_REASON_LENGTH` | 3 characters (a picture is also required) |
| Laptop model for managers only (set `false` to give it to everyone) | `Code.gs` → `LAPTOP_FOR_MANAGERS_ONLY` | `true` |
| Relations in the emergency-contact drop-down ("Other" is added by the app) | `Code.gs` → `RELATIONS` | Wife, Son, Brother, Mother, Father, Daughter |
| Night-hold window | `Code.gs` → `NIGHT_OPEN_HOUR`, `NIGHT_CLOSE_HOUR` (and `OPEN_HOUR` / `CLOSE_HOUR` in `NightHoldActivity.kt`) | `21` and `6` (9 PM to 6 AM) |
| Biggest in-app update accepted | `Code.gs` → `UPDATE_MAX_BYTES` | 40 MB |
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
| A leave request is refused with *"write the reason"* or *"attach a picture"* | Both are required: a reason of 3+ characters and a picture of the leave application. |
| *Take photo* does nothing on the Leave screen | Allow the Camera permission when Android asks (or in Settings → Apps), or use *Choose picture* instead. |
| The build is green but the *Publish release* step shows a red error | A pre-release with files was refused by GitHub (immutable releases). The APK is still in the run's **Artifacts**; the current `build.yml` saves such builds as a draft release instead. |
| No **Update** card on Home although I published a version | The published *build number* must be higher than the installed one (shown in Admin → App). Refresh Home (⟳). |
| *"App not installed as package conflicts"* when updating | The new APK was signed with a different key (for example a `…-TEMP-KEY.apk`). Add the four signing secrets, rebuild, uninstall the app once, install, and publish updates from then on. |
| Update download fails or says *damaged* | Check the internet and tap Update again. If it keeps failing, upload the APK again from Admin → App. |
| Someone forgot their password | Admin Panel → Users → **Reset password** (this also lifts a *too many wrong passwords* pause). |
| The Admin Panel isn't in the 👤 menu | Their *Role* cell must say `admin`; then open or refresh Home once. |
| Someone lost their phone | In the Apps Script editor run `revokeOneUser` (edit the username inside it first), or `revokeEveryone`. |
| Build APK fails | Open the failed run's **Summary** — the *"Build failed – the real error"* box shows the exact line. The full log is saved as a `build-log` artifact. |
| Nothing is written to the Sheet | Make sure the script is bound to the Sheet or `SHEET_ID` is set, and that `setup` was run once. |

---

## 🔒 Security notes

- **Passwords** are stored only as salted PBKDF2-SHA256 hashes. The app never saves your password; it keeps a signed token (encrypted with the Android Keystore) that expires after 30 days, is renewed while you keep using the app, and stops working the moment you change your password, log out, or an admin revokes it.
- **Brute-force protection:** 5 wrong passwords pause that account for 15 minutes. Apps Script cannot see a caller's IP address, so this is per account, not per attacker; an attacker can pause someone's account, but cannot get into it.
- **Selfies, leave applications and locations are validated on the server:** photo required, JPEG only, size-limited; coordinates must be real and inside the allowed area; the phone's "fake GPS" flag is rejected. A phone with a modified app or a rooted device can still lie about its GPS — only Play Integrity attestation would close that, and it is not included.
- **Profiles** are stored on a **Profiles** tab (created automatically, one row per person, plain-text cells). A profile can be changed only by its owner **or an admin**; a profile photo is visible only to that person, the supervisors above them, and admins.
- **Spreadsheet formula injection** is blocked: text typed on a phone (address, hotel, leave reason) is always stored as text.
- **Admin actions** are checked on the server on every request (the *Role* cell is read each time, so removing `admin` takes effect at once), and adding a user or resetting a password needs the admin's own password again. A password an admin sets is temporary: the person must choose their own at first sign-in. Only someone who can edit the Sheet can create the first admin.
- **Authorization** is enforced on the server: selfies and leave-application pictures are visible only to the person and the supervisors above them; leave can be decided only by a supervisor above that person in the chain (never for yourself); team views only down your own chain.
- **App updates** are published only by an admin, who must confirm with their own password. Phones download them only when signed in, verify a SHA-256 fingerprint, and Android installs them only if they carry the same signing key as the installed app.
- **Backups are disabled** for the app, and saved data on the phone is encrypted and wiped at logout.
- The script URL is public (anyone who has the APK has it), so assume anyone can call it. Everything above is built with that in mind.
- Attendance time is stamped by Google's server. Leave balances reset each calendar year and count **Approved + Pending** requests. A manager is anyone who appears in another user's **Manager** column.
- Keep the repository **private** or limit who has write access: anyone who can push can change the app, and the *Apply Update* workflow applies uploaded zips automatically.

---

## 📁 Project layout

```
.github/workflows/
  build.yml            Builds the APK, numbers it, signs it, publishes it
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
      AdminActivity.kt  Admin Panel: users, notices, holidays, publish app update
      NoticesActivity.kt  List of notices
      NoticeAlerts.kt   Bell count and the sign-in notice pop-up
      Photos.kt         Selfie / leave-application / profile picture loader and viewer
      ProfileActivity.kt  My Profile: photo, phones, email, emergency contact, bike, laptop
      AppUpdate.kt      In-app update: download, verify, install
      Ui.kt             Shared header + bottom bar
      MainActivity.kt           Home
      AttendanceActivity.kt     Selfie + location check-in
      LeaveActivity.kt          Leave + balances + application picture
      NightHoldActivity.kt      Night hold
      CalendarActivity.kt       Calendar
      TeamActivity.kt           Manager team view + approvals
      …                         Login, photos, pickers, dialogs
    res/                Layouts, colors, themes, drawables, xml/file_paths.xml (camera photo sharing)
```

---

## 📄 License

Personal / internal use project. Adjust and reuse as needed.
