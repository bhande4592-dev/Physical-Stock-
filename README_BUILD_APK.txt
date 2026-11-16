RM PHYSICAL STOCK VERIFICATION - ANDROID APP (APK) BUILD GUIDE
================================================================

WHAT THIS IS
  A real Android app (package com.rmstock.verification, app name "RM Stock").
  It contains your HTML app (app/src/main/assets/index.html) and works fully OFFLINE.
  Works on Android 5.0 (API 21) and newer: phones, tablets, rugged handheld scanners.

OPTION A - BUILD WITH ANDROID STUDIO (recommended, ~10 minutes the first time)
  1. Install Android Studio (free): https://developer.android.com/studio
  2. File > Open > choose this folder (RM_Stock_Android_Project). Wait for "Gradle sync" to finish
     (needs internet once to download libraries). If asked to install SDK 34, click Install.
  3. Menu: Build > Build Bundle(s) / APK(s) > Build APK(s).
  4. Click "locate" in the pop-up. File = app/build/outputs/apk/debug/app-debug.apk
  5. Send app-debug.apk to the phone (WhatsApp / e-mail / USB / Drive) and tap it to install.
     If Android says "Install blocked": Settings > allow "Install unknown apps" for the app you
     opened the APK from (Files / Chrome / WhatsApp), then tap the APK again.

OPTION B - BUILD WITHOUT ANDROID STUDIO (GitHub, free)
  1. Create a free GitHub account and a new repository.
  2. Upload ALL files of this folder (including the hidden folder .github) to the repository.
  3. Open the "Actions" tab > "Build APK" > Run workflow. After ~4 minutes open the finished run
     and download the artifact "RM-Stock-APK" (zip containing app-debug.apk).

PERMISSIONS (what the app asks and why)
  CAMERA .......... asked at first start and again whenever "Start camera scan" is tapped.
                    Needed to read the QR code. If you tap "Don't allow" twice, Android blocks it;
                    the app then shows "Open Settings" (also: Home screen > "Camera / app permissions").
  STORAGE ......... only on Android 9 or older, to save the Excel into Downloads.
                    Android 10+ needs no permission (file goes to Downloads automatically).
  VIBRATE ......... beep/vibrate feedback on scan (no pop-up).
  No internet permission is used. Data never leaves the phone unless you Share/Download the Excel.

HOW SCANNING WORKS IN THE APP
  - Tap "Start camera scan": full-screen native scanner opens (QR, Code128, Code39, DataMatrix).
    Volume-up = torch. After each scan the quantity sheet appears; after Save the scanner reopens
    automatically. Press Back in the scanner to stop.
  - Hardware scanner guns / handheld terminals with a scan trigger ("keyboard wedge" mode):
    just tap the "Scanner / type QR text" box once; the scan is typed in and Enter submits it.

WHERE THINGS ARE SAVED
  - Counts: inside the app (survive closing/restarting the phone). Uninstalling the app deletes
    them -> always press "Backup (JSON)" or download the Excel before uninstalling/updating by uninstall.
  - Excel / Backup files: phone's Downloads folder. "Share Excel" opens WhatsApp/e-mail/Drive chooser.

UPDATING THE APP LATER
  Replace app/src/main/assets/index.html with the new HTML, increase versionCode in app/build.gradle
  (e.g. 1 -> 2), build again, and install the new APK OVER the old one (do not uninstall; counts stay).

RELEASE / COMPANY DISTRIBUTION
  The APK from "assembleRelease" is signed with the debug key and installs fine on any phone.
  For Google Play or MDM, create your own keystore (Build > Generate Signed Bundle / APK).
