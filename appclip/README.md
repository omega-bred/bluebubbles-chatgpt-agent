# BlueChatAI iOS App Clip

This subproject contains a minimal SwiftUI container app and App Clip target for BlueChatAI.

- App Store Connect app name: `BlueChatAI`
- App Store Connect app ID: `6775352127`
- Main app bundle ID: `land.bre.bluechat.ios`
- App Clip bundle ID: `land.bre.bluechat.ios.Clip`
- App Store Connect main bundle resource ID: `SRKX6DJQPJ`
- App Store Connect App Clip bundle resource ID: `9657HFRZMD`
- Apple team ID: `U2Q8X6GTU9`
- Associated domain: `bluechat.bre.land`
- StoreKit product ID: `land.bre.bluechat.premium.monthly`
- StoreKit subscription group ID: `22126189`
- StoreKit subscription ID: `6775352293`

The App Clip expects an invocation URL containing `token=...`, exchanges it with the backend at
`/api/v1/appClip/createSession.appClipSessions`, then authenticates existing website/subscription
APIs using the returned `X-App-Clip-Session` token.

App Store Connect setup already done through `asc`:

- Created both bundle IDs.
- Enabled Associated Domains on both bundle IDs.
- Created the `Premium` auto-renewable subscription group.
- Created the `Premium Monthly` subscription at $5.00/month in the United States.
- Uploaded `appclip/AppStoreConnect/subscription-review.png` as the subscription review screenshot.

Remaining App Store Connect setup before distribution:

- Link the App Clip bundle ID to the parent app bundle ID. The public App Store Connect API does not
  expose the App Clip `parentBundleId` relationship; `asc web bundle-ids capabilities sync-app-clip`
  can do this only with an interactive Apple web session, or it can be done manually in the web UI.
- Create the App Clip advanced experience for `https://bluechat.bre.land/account/link`.
- Add production signing profiles/icons that Xcode requires for archives.
- Keep `APPLE_APP_ID` set to `6775352127` in production; this is not secret and is already in the
  Kubernetes manifest.

The backend serves the Apple app site association document for the domain.

Build locally with:

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -project appclip/BlueChat.xcodeproj \
  -scheme BlueChatClip \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```
