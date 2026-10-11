# 📋 SAM BOND — Attendance App

An Android attendance app for **SAM BOND**: selfie + live GPS check-in, late-arrival detection, leave requests with yearly balances, overnight **Night Hold** reporting, and a multi-level manager hierarchy with approvals — all backed by a **Google Sheet**. No server or database to run, and APKs are built automatically by **GitHub Actions**.

> Android 7.0+ · Kotlin · Google Apps Script backend · Pay period **26th → 25th** · Timezone **Asia/Dhaka**

---

## ✨ Features

### For every employee
- **Login / Logout / Change password** — usernames and passwords live in a Google Sheet; staff can change their own password from the app.
- **Selfie attendance** — front-camera selfie, live GPS location, address lookup, and a **server-side** date/time stamp (changing the phone's clock does nothing).
- **Location is required** — a submission without a real GPS location is rejected by the server, not just the app.
- **Late Present** — submitting at or after **9:00 AM** is marked *⏰ Late Present* with the time.
- **Absent** — still unmarked after **6:00 PM** counts as *Absent*; past unmarked days show as Absent too. **Friday is an automatic weekly off** and is never counted as absent.
- **One check-in per day** — a second submission on the same day is blocked.
- **Leave requests** — Casual, Sick or Earn leave with a from/to date picker; days are counted automatically.
- **Leave balance** — **Casual 10 · Sick 14 · Earn 17** days per year, with *left of total · used* shown per type.
- **Attendance ↔ leave conflict checks** — you can't mark attendance on an approved-leave day, or apply for leave on a day you already marked present.
- **Night Hold** — staying away overnight? Report it with:
  - live GPS (fake/mock locations are rejected),
  - **district → thana** pickers (all **64 districts**, with searchable lists),
  - **hotel name**,
  - **one per day**; the form can't be submitted without real data (checked again on the server).
- **Calendar** — the pay period (26th–25th) at a glance: present, late, leave, Friday off, absent, and 🌙 night-hold days. Tap any date for details, including that day's night-hold status.

### For managers
- **Unlimited levels** — everything uses one *Manager* column, so Employee → ZM/ASM → RSM/DSM → SM → Operation Manager (or any structure) works with no hardcoded roles.
- **My Team** — each direct report's status today (Present / Late / On Leave / Absent / Off / Pending), their selfie and location, and a live roll-up for any report who manages a team.
- **Drill down** — tap any manager to see their team, all the way to individual employees.
- **Approve / Reject leave** — only for your **own direct reports**; higher-level views are read-only, so approvals always follow the chain of command.

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

A **bottom bar** (Home · Attendance · Leave · Team · Calendar · Night Hold) is on every screen, and every screen has a compact header that stays fixed while the content scrolls. ⟳ buttons refresh any screen, and the 👤 menu on Home has *Change Password*, *Logout* and the app version.

---

## 🛠 Tech

| Part | Technology |
|---|---|
| App | Kotlin, AppCompat, Material Components |
| Camera | CameraX |
| Location | Google Play Services (fused location) |
| Backend | Google Apps Script web app (`Code.gs`) |
| Data | Google Sheets (+ Google Drive for selfies) |
| Build | GitHub Actions → debug APK |

Speed: screens open instantly from a saved copy of the last response and then refresh; selfies are downloaded once and cached; uploads are shrunk before sending.

---

## 🚀 Setup

### 1. Google Sheet + Apps Script
1. Create a new Google Sheet.
2. **Extensions → Apps Script**, paste in `Code.gs`, and save.
3. If the script is **not** opened from inside the Sheet, set `SHEET_ID` at the top of `Code.gs` to the ID in the sheet's URL (the text between `/d/` and `/edit`).
4. Run the **`setup`** function once and approve the permission prompts. It creates:
   - **Users** — `Username | Password | Name | Manager` (*Manager* = another user's username, blank if none; this builds the hierarchy)
   - **Attendance** — filled in as staff check in
   - **Leaves** — filled in as staff apply; the **Status** column (Pending / Approved / Rejected) is set by a manager in the app, or by hand
   - **NightHold** — created automatically on first use
5. Add your staff to **Users** and link the **Manager** column.
6. **Deploy → New deployment → Web app** — *Execute as* **Me**, *Who has access* **Anyone**. Copy the URL ending in `/exec`.

### 2. Connect the app
Open `app/src/main/java/com/example/attendance/Api.kt`, paste the URL into `Config.SCRIPT_URL`, and commit.

### 3. Build the APK
- Push to the repo or run **Actions → Build APK → Run workflow**.
- Download the APK from the run's **`app-debug`** artifact, or from the repo's **Releases** page (easiest on a phone).
- Install it on an Android phone (Android 7.0+).

---

## 🔢 Versions & updates

- Every build is numbered **`1.0.<run number>`** (for example `1.0.56`). The APK is named `SAM-BOND-Attendance-v1.0.56.apk` and is also published on the **Releases** page.
- The version shows on the login screen and in the 👤 menu on Home.
- All builds are signed with **one fixed key** (`app/debug.keystore`), and each build has a higher version number than the last — so a new APK installs **over** the old one as a normal update.
- ⚠️ **One-time step:** if you installed an APK built *before* the fixed key was added, Android shows *"Conflicting app signatures"*. Uninstall the old app once, then install the new one. After that, updates just work. Your data is safe — it all lives in the Google Sheet.
- The keystore is a **debug/test key** with a published password. Fine for internal distribution; use a private release key if you ever publish to the Play Store.

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
| Late cutoff | `Code.gs` → `LATE_HOUR` | `9` (9:00 AM) |
| Absent cutoff | `Code.gs` → `ABSENT_AFTER_HOUR` | `18` (6:00 PM) |
| Pay period | `Code.gs` → `PERIOD_START_DAY` / `PERIOD_END_DAY` | 26 → 25 |
| Weekly off | `Code.gs` → `isFridayDate` | Friday |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |
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
| Build APK fails | Open the failed run's **Summary** — the *"Build failed – the real error"* box shows the exact line. The full log is saved as a `build-log` artifact. |
| Nothing is written to the Sheet | Make sure the script is bound to the Sheet or `SHEET_ID` is set, and that `setup` was run once. |

---

## 🔒 Notes

- Passwords are stored as plain text in the **Users** sheet — fine for a small trusted team, not for sensitive use without extra hardening.
- Attendance time is stamped by Google's server and location is validated server-side, so neither can be faked from the app alone.
- Selfies are saved in a Google Drive folder named **AttendanceSelfies**; a manager can only view selfies of people below them in the hierarchy.
- Leave balances reset each calendar year and count **Approved + Pending** requests.
- A manager is anyone who appears in another user's **Manager** column — no separate role flag.

---

## 📁 Project layout

```
.github/workflows/
  build.yml            Builds the APK, numbers it, publishes it
  apply-update.yml     Unpacks an uploaded update.zip into the project
app/
  debug.keystore       Fixed signing key (debug/test)
  build.gradle
  src/main/
    AndroidManifest.xml
    assets/bd_locations.txt     64 districts → thanas
    java/com/example/attendance/
      Api.kt            Server URL, session, API call helper
      Cache.kt          Saved-response cache for instant screens
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
