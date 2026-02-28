# Changelog

## [1.0.8] - 2026-02-28

### Bug Fixes
- **Build Architecture**: Fix architecture detection for Zapstore

## [1.0.7] - 2026-02-28

### New Features

- **Image Filters**: Added image filters that can be applied to photos before posting.

- **Profile Image Viewer**: View images fullscreen directly from a user's profile page.

### Improvements

- **User Search**: Further improvements to user search reliability. Removed relay.nostr.band dependency.

### Bug Fixes

- **App Icon**: Fixed the app icon.

### Under the Hood

- **Release Pipeline**: Added GitHub Actions release workflow for automated builds.

## [1.0.6] - 2026-02-22

### Improvements

- **User Search**: Replaced the nostr.band HTTP API search with NIP-50 relay-based search, providing more reliable user discovery directly through the Nostr protocol.

- **Auto-Reconnect**: Added automatic relay reconnection every 60 seconds when disconnected relays are detected, improving connection stability during extended use.

### Under the Hood

- **EOSE Support**: The Nostr client now emits end-of-stored-events (EOSE) signals, enabling search to know when results are complete.

- **Raw Filter Subscriptions**: Added support for raw JSON filter subscriptions (e.g., NIP-50 search filters) with optional relay targeting.

## [1.0.5] - 2026-02-14

### New Features

- **Profile Picture as Settings Icon**: The settings cog icon in the feed top bar is replaced with your profile picture when one is set.

- **Profile Picture Upload**: Added an upload button next to the Profile Picture URL field in Edit Profile. Tap the avatar preview or the upload button to pick an image from your gallery and upload it to a Blossom server. Supports both Amber and local key signing flows.

- **Camera Posts**: Take photos directly from the camera and post them without leaving the app.

- **Zap Totals**: Zap amounts are now displayed alongside posts in the feed.

### Bug Fixes

- **Profile Loading Reliability**: Fixed an issue where the Edit Profile fields would sometimes appear empty, particularly when the trending feed was active. The profile editor now uses cached metadata for instant display and properly terminates its relay subscription.

- **Profile Picture on Startup**: Fixed the user's profile picture not appearing in the top bar on app launch by fetching the current user's metadata (Kind 0) at startup.

## [1.0.4] - 2026-02-09

### New Features

- **Bottom Navigation Bar**: Added bottom bar with Home, Wallet, and Notifications tabs for easier navigation.

- **Notifications**: Track reactions, zaps, and mentions on your posts. Notification state persists across app restarts. Home icon shows a red dot when new posts arrive.

- **NWC Wallet**: Added Nostr Wallet Connect support with QR code scanning, balance display, transaction history, and Lightning invoice payments.

- **Video Posts**: Support for uploading and viewing video posts.

- **Multi-Image Posts**: Handle posts containing multiple images.

- **Mute/Unmute Users**: Added ability to mute and unmute users.

- **Following & Muted Lists**: View your following and muted user lists in Settings.

### Bug Fixes

- **Reaction Counts**: Fixed reaction counts to show totals from all users, not just your own.

- **Following Feed Initialization**: Fixed an issue where the following feed wasn't initialized correctly on startup.

- **Notifications**: Fixed notification delivery and tracking issues.

## [1.0.3] - 2026-02-05

### New Features

- **Fullscreen Image Viewer**: Tap on any image to view it fullscreen.

### Bug Fixes

- **Relay Connection Stability**: Fixed issues with losing connection to relays, improving overall reliability.

- **Image Rotation**: Fixed images appearing rotated incorrectly by properly handling EXIF orientation data.

- **EXIF Privacy**: Images now have EXIF metadata stripped before uploading, protecting location and device information.

- **Kind 1 Post Interoperability**: Added support for displaying images from standard kind 1 text notes, improving compatibility with other Nostr clients.

## [1.0.2] - 2026-02-03

### New Features

- **Follow/Unfollow Users**: Added ability to follow and unfollow users from their profile page. Follow status syncs with your Nostr contact list (kind 3 events) and works with both Amber and local key signing.

- **Following Feed**: New "Following" feed mode shows posts only from users you follow. The app defaults to Following feed when you have follows, with a dropdown to switch between Following and Trending.

### Bug Fixes

- **Amber Like Signing**: Fixed like button not working when signed in with Amber. The app now correctly handles both full signed event JSON and signature-only responses from Amber.

- **Like State Persistence**: Fixed liked posts not showing as liked after refresh or navigation. The app now subscribes to your own reactions (kind 7 events) to track and persist liked state.

- **Amber Signing Consistency**: Applied the same signing fixes across all Amber operations including follow/unfollow, create post, and profile editing.

## [1.0.1] - 2026-02-02

### Bug Fixes

- **Amber Login**: Fixed Amber (NIP-55) sign-in not working properly. The app now correctly uses the Activity Result API to receive the public key from Amber, supporting both npub and hex formats.

- **Amber Return Navigation**: Fixed issue where the app would not return from Amber after authorization. Changed activity launch mode and implemented proper callback handling.

- **Username Display**: Fixed usernames showing as npub instead of display names. Added shared metadata caching across services for consistent user profile display.

- **Backup Warning Dialog**: Fixed backup warning dialog closing prematurely when creating a new account, ensuring users can copy their nsec before proceeding.
