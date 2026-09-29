# 📋 Attendance App

An Android attendance app with selfie + GPS check-in, leave requests with yearly balances, a line-manager approval flow, and a monthly calendar — all backed by a Google Sheet, no server or database to run.

## ✨ Features

- **Login / Logout** — staff sign in with a username and password stored in a Google Sheet
- **Selfie attendance** — front camera selfie, GPS location, address lookup, and a server-side date/time stamp, all in one tap
- **Location required** — attendance can't be submitted without a valid GPS location, enforced on the server so it can't be bypassed by a modified app
- **One check-in per day** — a second submission on the same day is blocked
- **Leave requests** — apply for Casual, Sick, or Earn leave with a from/to date picker; total days are calculated automatically
- **Leave balance** — 10 days Casual, 14 days Sick, 17 days Earn per year, with used / pending / remaining shown per type and a running total
- **Attendance ↔ leave conflict checks** — can't mark attendance on a day you're on approved leave, and can't apply for leave on a day you already marked present
- **Line managers & approvals** — staff can be assigned a manager; managers get a "My Team" screen showing each report's today status (Present / On Leave / Pending) and can Approve or Reject leave requests directly from the app
- **Attendance calendar** — a month view showing present days (green) and leave days (orange/yellow), tap any day for details
- **All data in Google Sheets** — attendance, users, and leave requests are just rows in a spreadsheet you can view, export, or edit anytime

## 📱 Screens

| Screen | What it does |
|---|---|
| Login | Sign in with username/password |
| Home | Today's status, leave balance summary, quick links |
| Mark Attendance | Selfie + location capture and submit |
| Leave | Apply for leave, see balance and request history |
| Calendar | Month view of attendance and leave |
| My Team *(managers only)* | Team's today status + approve/reject leave requests |

## 🛠 Tech

- **App:** Kotlin, CameraX (selfie), Google Play Services (location)
- **Backend:** Google Apps Script (`Code.gs`), attached to a Google Sheet
- **Build:** GitHub Actions builds the debug APK automatically (`.github/workflows/build.yml`)
- **Remote updates:** dropping an `update.zip` at the repo root and pushing it auto-unpacks into the project (`.github/workflows/apply-update.yml`) — useful for applying changes from a phone without a computer

## 🚀 Setup

### 1. Google Sheet + Apps Script
1. Create a new Google Sheet.
2. Open **Extensions → Apps Script**, paste in `Code.gs`, and save.
3. Run the `setup` function once (approve the permission prompts). This creates three tabs:
   - **Users** — `Username | Password | Name | Manager` (Manager = another user's username, blank if none)
   - **Attendance** — filled in automatically as staff check in
   - **Leaves** — filled in automatically as staff apply for leave; the **Status** column (Pending / Approved / Rejected) is set by a manager in the app, or by hand in the sheet
4. Add staff to **Users**. Set each person's **Manager** to their line manager's username to enable the team/approval features for that manager.
5. **Deploy → New deployment → Web app**, set *Execute as* to **Me** and *Who has access* to **Anyone**. Copy the web app URL (ends in `/exec`).

### 2. Connect the app
1. Open `app/src/main/java/com/example/attendance/Api.kt`.
2. Paste your web app URL into `Config.SCRIPT_URL`.
3. Commit the change.

### 3. Build the APK
- Push to this repo, or run the **Build APK** workflow manually from the **Actions** tab.
- Download the `app-debug` artifact once the run finishes, extract it, and install `app-debug.apk` on an Android phone (Android 7.0+).

## ⚙️ Configuration

| Setting | Where | Default |
|---|---|---|
| Sheet ID | `Code.gs` → `SHEET_ID` | your sheet's ID |
| Timezone | `Code.gs` → `TZ` | `Asia/Dhaka` |
| Leave quotas | `Code.gs` → `QUOTA` | Casual 10, Sick 14, Earn 17 (days/year) |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |

**After any change to `Code.gs`:** saving isn't enough — go to **Deploy → Manage deployments → ✏️ → Version: New version → Deploy** so the live URL picks up the change.

## 🔒 Notes

- Passwords are stored as plain text in the **Users** sheet — fine for a small trusted team, not recommended for sensitive use without further hardening.
- Attendance date/time is stamped by the Google server, not the phone, so it can't be faked by changing the phone's clock.
- GPS location is validated on the server; a submission without a real location is rejected there, not just in the app UI.
- Selfies are saved to a Google Drive folder named **AttendanceSelfies**, linked from each attendance row.
- Leave balances reset each calendar year and are based on **Approved** + **Pending** requests for that year.
- A manager is anyone who appears in another user's **Manager** column — no separate role flag needed.

## 📄 License

Personal / internal use project. Adjust and reuse as needed.
