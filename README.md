# 📋 SAM BOND — Attendance App

An Android attendance app for SAM BOND with selfie + GPS check-in, late-arrival detection, leave requests with yearly balances, a multi-level manager approval hierarchy, and a monthly calendar — all backed by a Google Sheet, no server or database to run.

## ✨ Features

- **Login / Logout / Change Password** — staff sign in with a username and password stored in a Google Sheet, and can change their own password from the app
- **Selfie attendance** — front camera selfie, GPS location, address lookup, and a server-side date/time stamp, all in one tap
- **Location required** — attendance can't be submitted without a valid GPS location, enforced on the server so it can't be bypassed by a modified app
- **Late Present detection** — a configurable cutoff time (default 9:00 AM) marks a submission as "⏰ Late Present" with the time, shown on the Attendance screen, Home, Calendar, and the manager's Team screen
- **One check-in per day** — a second submission on the same day is blocked
- **Leave requests** — apply for Casual, Sick, or Earn leave with a from/to date picker; total days are calculated automatically
- **Leave balance** — 10 days Casual, 14 days Sick, 17 days Earn per year, with used / pending / remaining shown per type and a running total
- **Attendance ↔ leave conflict checks** — can't mark attendance on a day you're on approved leave, and can't apply for leave on a day you already marked present
- **Multi-level manager hierarchy** — any number of levels (e.g. Employee → ZM/ASM → RSM/DSM → SM → Operation Manager), all using the same "Manager" link — no hardcoded roles
  - Each manager gets a **My Team** screen showing every direct report's today status (Present / Late / On Leave / Pending)
  - If a direct report is themselves a manager, their row also shows a live roll-up of their entire team
  - Tap any manager's row to drill down into their team, all the way to individual employees
  - **Approve / Reject leave** — only shown for your own direct reports; higher-level views are read-only, so approvals always follow the correct chain of command
- **Attendance calendar** — a month view showing present days (green), late days (orange), and leave days (amber), tap any day for details
- **Refresh on demand** — a ⟳ button on Home, Calendar, Leave, and My Team reloads that screen's data without leaving it
- **All data in Google Sheets** — attendance, users, and leave requests are just rows in a spreadsheet you can view, export, or edit anytime

## 📱 Screens

| Screen | What it does |
|---|---|
| Login | Sign in with username/password |
| Home | Today's status, leave balance summary, quick links, change password / logout |
| Mark Attendance | Selfie + location capture and submit |
| Leave | Apply for leave, see balance and request history |
| Calendar | Month view of attendance and leave |
| My Team *(managers only)* | Team's today status, roll-ups, drill-down, and leave approvals |

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
   - **Users** — `Username | Password | Name | Manager` (Manager = another user's username, blank if none — this is what builds the hierarchy)
   - **Attendance** — filled in automatically as staff check in
   - **Leaves** — filled in automatically as staff apply for leave; the **Status** column (Pending / Approved / Rejected) is set by a manager in the app, or by hand in the sheet
4. Add staff to **Users**. Chain the **Manager** column to build your org structure (Employee → ZM/ASM → RSM/DSM → SM → Operation Manager, or however many levels you need).
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
| Late cutoff | `Code.gs` → `LATE_HOUR` | `9` (9:00 AM, 24-hour clock) |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |

**After any change to `Code.gs`:** saving isn't enough — go to **Deploy → Manage deployments → ✏️ → Version: New version → Deploy** so the live URL picks up the change.

## 🔒 Notes

- Passwords are stored as plain text in the **Users** sheet — fine for a small trusted team, not recommended for sensitive use without further hardening. Staff can change their own password from the app's Home menu.
- Attendance date/time is stamped by the Google server, not the phone, so it can't be faked by changing the phone's clock.
- GPS location is validated on the server; a submission without a real location is rejected there, not just in the app UI.
- Selfies are saved to a Google Drive folder named **AttendanceSelfies**, linked from each attendance row.
- Leave balances reset each calendar year and are based on **Approved** + **Pending** requests for that year.
- A manager is anyone who appears in another user's **Manager** column — no separate role flag needed, and it works at any number of levels.
- Leave approvals always go through the direct manager relationship, even when a higher-level manager is viewing a drilled-down team — this keeps approvals honest to the org chart.
- Each Team screen load reads the Users/Attendance/Leaves sheets once and reuses that data for every roll-up, so performance doesn't degrade as the hierarchy or headcount grows.

## 📄 License

Personal / internal use project. Adjust and reuse as needed.
