# FaceLock Pro — Android 10+

FaceLock Pro uses Android's own biometric prompt and device credential. It does not store face photographs or pretend ordinary camera face detection is secure identity verification.

## Features
- Lock device now: asks Android to show its normal secure lock screen using Device Admin force-lock. Android remains responsible for unlocking.
- Protect selected apps: after explicit Usage Access and Display-over-other-apps permissions, monitors foreground package events and displays a full-screen lock overlay. Tapping it opens AndroidX BiometricPrompt.
- Biometric fallback: uses biometrics exposed by Android plus the device PIN, pattern, or password.
- Local-first: no account, server, network permission, face-photo storage, or cloud upload.
- Recovery: Settings, launcher, System UI, and core phone apps are excluded from the picker. Monitoring can be stopped or permissions revoked in Android Settings.

## Security boundaries
1. A normal third-party app cannot replace Android's real lock screen or enroll a new system face. Device lock uses the manufacturer's Keyguard.
2. Multiple faces, when supported by the firmware, must be enrolled in Android Settings. FaceLock cannot read, name, or count those enrolled faces.
3. Some manufacturers' face-unlock features are not exposed to third-party apps through BiometricPrompt. FaceLock displays the Android prompt and never fakes a successful face match.
4. App protection is an additional convenience layer, not a tamper-proof kiosk or enterprise device-owner policy. It depends on Usage Access, overlay permission, and OEM background-service behavior. A user with physical control can disable permissions or uninstall the app.
5. A secure device PIN, pattern, or password is required before protection starts. This is the recovery route if biometric authentication is unavailable or temporarily locked out.

## Build
Project directory: facelock/
- Minimum SDK 29 (Android 10), compile/target SDK 35
- JDK 17, Gradle 8.9
- Build: gradle --no-daemon -p facelock assembleDebug
The GitHub Actions workflow creates a debug-signed APK artifact for testing. A production release requires a private release key owned by the publisher.

## First-time setup
1. Install the APK and set a secure screen PIN/pattern/password in Android Settings.
2. Enroll the biometrics supported by the device in Android Settings.
3. Run the biometric test in FaceLock.
4. For app protection, choose apps, grant Usage Access, grant Display over other apps, and press Start app protection.
5. For device locking, approve the Device Admin request when you use Lock device now.

Do not protect Settings, the home launcher, System UI, or phone/emergency apps.
