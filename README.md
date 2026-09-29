# 📋 Attendance App

A simple Android attendance app with selfie capture, GPS location, login, leave requests, and a Google Sheet as the backend. No server or database setup needed — everything is stored in a Google Sheet you control.

## ✨ Features

- **Login / Logout** — staff sign in with a username and password stored in a Google Sheet
- **Selfie attendance** — front camera selfie, GPS location, address lookup, and server-side date/time, all in one tap
- **One check-in per day** — a second submission on the same day is blocked
- **Leave requests** — apply for Casual, Sick, or Earn leave with a from/to date picker
- **Leave balance** — 10 days Casual, 14 days Sick, 17 days Earn per year, with used/pending/remaining shown per type
- **Approvals** — leave requests are Pending until an admin marks them Approved or Rejected directly in the sheet
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

## 🛠 Tech

- **App:** Kotlin, CameraX (selfie), Google Play Services (location)
- **Backend:** Google Apps Script (`Code.gs`), attached to a Google Sheet
- **Build:** GitHub Actions builds the debug APK automatically (see `.github/workflows/build.yml`)

## 🚀 Setup

### 1. Google Sheet + Apps Script
1. Create a new Google Sheet.
2. Open **Extensions → Apps Script**, paste in `Code.gs`, and save.
3. Run the `setup` function once (approve the permission prompts). This creates the **Users**, **Attendance**, and **Leaves** tabs.
4. Add staff to the **Users** tab: `Username | Password | Name`.
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
| Timezone | `Code.gs` → `TZ` | `Asia/Dhaka` |
| Leave quotas | `Code.gs` → `QUOTA` | Casual 10, Sick 14, Earn 17 (days/year) |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |

## 🔒 Notes

- Passwords are stored as plain text in the **Users** sheet — fine for a small trusted team, not recommended for sensitive use without further hardening.
- Attendance date/time is stamped by the Google server, not the phone, so it can't be faked by changing the phone's clock.
- Selfies are saved to a Google Drive folder named **AttendanceSelfies**, linked from each attendance row.
- Leave balances reset each calendar year and are based on **Approved** + **Pending** requests.

## 📄 License

Personal / internal use project. Adjust and reuse as needed.
