# Changelog

## [1.0.1] - 2026-02-02

### Bug Fixes

- **Amber Login**: Fixed Amber (NIP-55) sign-in not working properly. The app now correctly uses the Activity Result API to receive the public key from Amber, supporting both npub and hex formats.

- **Amber Return Navigation**: Fixed issue where the app would not return from Amber after authorization. Changed activity launch mode and implemented proper callback handling.

- **Username Display**: Fixed usernames showing as npub instead of display names. Added shared metadata caching across services for consistent user profile display.

- **Backup Warning Dialog**: Fixed backup warning dialog closing prematurely when creating a new account, ensuring users can copy their nsec before proceeding.
