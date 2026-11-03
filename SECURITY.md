# 🔒 Security Policy

**SAM BOND Attendance** is an Android app, a Google Apps Script server (`Code.gs`) and a Google Sheet. It holds staff names, selfies and locations, and, for people an admin switches on, their movement. A security problem here is a privacy problem for real people.

This page says **how to report a problem**, **what the system does to protect people**, **what it cannot protect**, and **what the owner has to keep doing**. The Wiki page **Security** has the same facts with more detail for admins.

---

## 📬 Reporting a security problem

**Please report it privately.** Do not open a public issue, write it in a group chat, or put details in a pull request or a screenshot that others can see.

1. On GitHub: open this repository's **Security** tab and choose **Report a vulnerability** (this works once the owner has switched private reporting on), **or**
2. Tell the app owner or an admin directly, in a private message or in person.

Please include:

- what you found, and what someone could read, change or do with it
- the steps to repeat it, and which screen or request is involved
- the app version and the date and time it happened, so the server log can be found
- **no real passwords, sign-in tokens, selfies or other people's data**: describe them instead

What you can expect from us:

- an answer within **3 days** saying we received it
- a first assessment within **7 days**
- a fix, or a clear plan for one, as soon as it is practical: serious problems first
- credit for the report, if you want it

Please do **not**:

- try passwords on accounts that are not yours, or use real people's data to test
- flood the server (Apps Script has no per-caller limits, so a flood slows everyone down)
- keep, copy or share any personal data you happened to see: tell us instead
- share details with others before we have had a fair time to fix the problem

---

## ✅ Supported versions

Only the **newest build** gets fixes. The app updates from inside: an admin publishes an update, and can make it **compulsory**, so phones with an older build stop on an *Update required* screen until they install it. If you are testing an older build, update first and check that the problem is still there.

---

## 🛡️ How the system protects people

### Sign-in and passwords

- Passwords are stored only as salted **PBKDF2-HMAC-SHA256** hashes (150,000 rounds). The app never saves the password: after sign-in it keeps a **signed token** in encrypted storage (AES-256, key held in the Android Keystore).
- The token lasts **30 days** and renews while the app is used (**12 hours**, no renewal, when *Keep me signed in* is off). Older sign-ins stop working at once when the person changes their password (the phone that changed it gets a fresh one) or logs out, when an admin signs them out or switches the account to *Inactive*, or when everyone is signed out.
- New passwords need at least **8 characters**. An easy one (`1234`, `12345678`, the username…) or a temporary one set by an admin works **once**: after that the server refuses everything except choosing a new password.
- **5 wrong passwords pause that account for 15 minutes.** The person (if an e-mail is saved in their profile) and the admins get an e-mail about it. Sign-in does the same amount of work whether or not the username exists.
- **Forgot password:** a 6-digit code goes to the e-mail saved in the profile. It works for 10 minutes, allows 5 tries, and only for the address it was mailed to. At most 3 codes an hour for one account and 20 an hour for the whole app.

### Admin powers

- Admin rights are read from the Sheet on **every request**, so removing `admin` takes effect at once. Only someone who can edit the Sheet can make the first admin.
- Risky actions need the admin's **own password**: for example who may use which menu, switching a person Active/Inactive, notices for everyone, editing someone's profile or photo, adding a user, resetting a password, resetting a face reference, SMS settings, manual entry and publishing an app update. After one correct password the server gives the app a **15-minute proof**, tied to that admin and that sign-in; no other token can pass as it.
- Temporary passwords copied in the Admin Panel are wiped from the clipboard after one minute.

### On the phone

- HTTPS only. Android backups of the app are switched off.
- Saved data is encrypted. At sign-out the app wipes it, **and the whole cache folder** (selfies, downloaded reports, half-made uploads).
- The app holds **no shared secret**. It contains only the public address of the server, so assume anything inside the APK can be read.

### On the server

- Every rule is checked on the server, not in the app. Selfies and leave pictures are visible only to the person and the supervisors above them; team views only go down your own chain; leave is decided only by a supervisor above that person, never for yourself.
- A selfie must be a JPEG of limited size. A location must be real, inside the allowed area, and not flagged as a fake (mock) location.
- Text typed on a phone (address, hotel, reason…) is stored as plain text, so it cannot run as a spreadsheet formula.
- Selfies and files live in Google Drive folders of the account that runs the script. The script never makes a file public.

### App updates

- Only an admin can publish an update, with their own password. Phones download it only when signed in, check its **SHA-256 fingerprint**, and Android installs it only if it carries the **same signing key** as the installed app.
- With the Script Property `RELEASE_CERT_SHA256` set, the server also refuses an APK signed with any other key.
- The signing key and its passwords are kept as **GitHub Actions secrets**. The repository's `.gitignore` blocks `*.jks`, `*.keystore`, `*.p12` and `local.properties`.

### Personal data: what is kept and who can see it

- **Attendance:** time, selfie, location and address. Visible to the person, the supervisors above them and admins.
- **Face print:** made on the phone. Only the numbers are saved (the **FaceRefs** sheet), never the picture. It only **flags** a mismatch and never locks anyone out.
- **Movement:** only for people an admin ticked. The phone shows a permanent notification and asks the person once. Points are kept only after attendance and until 6 PM (a driver: until the Day End), and are readable only by the person, the managers above them and admins. Rows older than **90 days** are deleted.
- **Whoever can edit the Sheet can read all of it.**
- Tell your staff what is collected, and check what local law and your contracts say about location and face data.

---

## 🔑 Where each secret lives

| Secret | Where it lives | Never in |
|---|---|---|
| Signing key and its passwords | GitHub Actions secrets | the repo, README, wiki, chat |
| `TOKEN_SECRET` (signs sign-in tokens) | Script Properties (made automatically) | the code |
| `APP_KEY` (front-door key) | Script Properties **and** the Cloudflare Worker's secret | the app, the repo |
| SMS gateway key and Device ID | Script Properties (set in **Admin Panel → 📱 SMS**) | the app, the Sheet, the repo |
| `SHEET_ID` | Script Properties (better than the constant in the code) | a public place |

A secret that was ever pasted into a chat, an e-mail, a screenshot or the repository counts as **leaked**. Replace it; do not hope nobody saw it.

---

## 🚨 If something leaks

- **A password was seen or guessed:** the person picks a new one, or an admin uses **Reset password** (Admin Panel → Users). A new password ends every older sign-in.
- **A phone was lost or stolen:** in Admin Panel → Users tap **Make Inactive** (every phone signed in as that person is signed out at once), then reset the password and **Make Active** again when the person has a safe phone. Or run `revokeOneUser` in the Apps Script editor (edit the username inside it first).
- **An admin's account may be in the wrong hands:** change that password, run `revokeEveryone`, then run `securityCheck` and read the list of admins.
- **Everyone must be signed out:** run `revokeEveryone`. If the token secret itself leaked, also delete the `TOKEN_SECRET` property: a new one is made at the next request, and every old token and admin proof stops working.
- **`APP_KEY` leaked:** make a new key and set it in the Script Property **and** in the Worker's secret at the same time. Until both match, the app cannot connect.
- **The SMS gateway key leaked:** make a new key in the gateway, enter it in **Admin Panel → 📱 SMS** with your own password, then delete the old key in the gateway.
- **The signing key leaked:** this is serious. Anyone holding it can sign an app that Android accepts as an update. Make a new key, update the GitHub secrets and `RELEASE_CERT_SHA256`, and have everyone uninstall the old app and install the new one (Android refuses updates signed with a different key). Tell staff to install only from the in-app update or the repository's Releases page.
- **The GitHub or Google account was taken over:** change the password, check 2-Step Verification, sign out other sessions, then review recent commits and workflow runs (GitHub), or the deployments, Script Properties and Sheet sharing (Google). Replace every secret in the table above.

---

## 📋 Owner's checklist

- [ ] Turn on **2-Step Verification** on the Google account that owns the Sheet and the script, and on GitHub.
- [ ] Share the Sheet only with people who must edit it: **whoever can edit it can read every profile and change any account.**
- [ ] Keep the repository **private**, or limit who can write to it: anyone who can push can change the app, and *Apply Update* applies uploaded zips automatically.
- [ ] Never put the signing key, passwords or API keys in the repo, README or wiki.
- [ ] After each server update run **`securityCheck`**: it flags easy passwords, lists the admins and spots Manager loops. It never prints a password.
- [ ] Set the Script Properties `SHEET_ID` (then blank the constant in `Code.gs`) and `RELEASE_CERT_SHA256`.
- [ ] With the Cloudflare Worker, set `APP_KEY`, but only **after every phone has the version that talks to the front door**. Older versions call the script directly and would be refused.
- [ ] Delete sample accounts (`manager1`, `demo`) if an old install still has them, and `app/debug.keystore` if it is still in the repo.
- [ ] Switch people who left to **Inactive**, and use **Reset face** for them.

---

## ⚠️ Known limits

You do not need to report these: they are known, and the wiki says so too.

- **The script URL is public.** Anyone with the APK has it, so everything is built assuming anyone can call it.
- **Account pausing:** someone who knows a username can pause that account for 15 minutes by typing wrong passwords. They cannot get in.
- **Floods:** a flood of requests can slow everyone down, because Apps Script has no per-caller limits. The optional Cloudflare front door limits requests per address.
- **Fake GPS and old photos:** a modified app or a rooted phone can still lie about its location or send a stored photo. Only Play Integrity attestation would close this, and it is not included. Managers reviewing selfies and locations is the practical control.
- **No biometric app lock.** R8 shrinks the app, but the app's own code is not obfuscated. Photos cached on the phone are not separately encrypted; they are deleted at sign-out.
- **The face check** runs on the phone, so it is a clue for managers, not proof.

---

_Last reviewed: 2 November 2026._
