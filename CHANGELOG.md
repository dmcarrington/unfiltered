# Changelog

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
