# App Cloner v1.0 — LOCK-READY

Authorized Android app cloning toolkit.

Flow:
Master Android source → Clone configuration → GitHub Actions → APK/AAB artifact

This project is for apps whose source code you own or are authorized to modify.
It does not reverse-engineer or bypass third-party APK protections.

## GitHub
Push the whole repository to GitHub, then use:
Actions → Build App Clone → Run workflow.

The workflow accepts:
- Clone App Name
- Application ID
- Version Name
- Version Code
- APK/AAB
- Debug/Release

## Master template
For production cloning, replace the sample `app/` source with your authorized master
app and use the placeholders:
__APP_NAME__
__APPLICATION_ID__
__VERSION_NAME__
__VERSION_CODE__

## Signing
Never commit keystores. Configure signing through GitHub Actions Secrets when you
are ready for signed release builds.


## App Icon
The App Cloner launcher icon is included in `app/src/main/res/mipmap-*`.
The manifest references `@mipmap/ic_launcher`, so the icon is used by the
installed Android application.
