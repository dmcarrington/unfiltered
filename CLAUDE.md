# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Nostr-based Instagram clone for Android. A photo-sharing social app built on the Nostr protocol.

## Technology Stack

- **Platform**: Native Android
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Nostr Library**: rust-nostr Kotlin bindings (`org.rust-nostr:nostr`)
- **Image Hosting**: Blossom-compatible servers (nostr.build, etc.)
- **Authentication**: Amber (NIP-55) primary, nsec fallback

## Requirements

- **JDK**: 17-24 (Java 25 not yet supported by Gradle)
- **Android SDK**: API 35

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test

# Run single test class
./gradlew test --tests "com.nostr.unfiltered.TestClassName"

# Lint check
./gradlew lint

# Clean build
./gradlew clean
```

## Key Nostr NIPs

- **NIP-01**: Basic protocol (events, relays, subscriptions)
- **NIP-02**: Contact/follow lists (kind 3)
- **NIP-05**: DNS-based user verification
- **NIP-19**: Bech32 encoding (npub, nsec, note)
- **NIP-47**: Nostr Wallet Connect (zaps)
- **NIP-55**: Android signer integration (Amber)
- **NIP-68**: Picture-first feeds (kind 20 events)
- **NIP-94**: File metadata

## Architecture

MVVM pattern with Jetpack Compose:
- `ui/` - Compose screens and components
- `viewmodel/` - ViewModels with StateFlow
- `repository/` - Data layer (Nostr events, relays)
- `nostr/` - Nostr protocol implementation
- `di/` - Dependency injection (Hilt)

## Nostr Event Kinds Used

- Kind 0: User metadata (profile)
- Kind 1: Text notes
- Kind 3: Contact list (follows)
- Kind 7: Reactions
- Kind 20: Picture posts (NIP-68)
- Kind 10002: Relay list metadata

## Development Notes

- Keys stored in Android KeyStore
- Use Amber via NIP-55 Intents when available
- Default relays: wss://relay.damus.io, wss://relay.primal.net, wss://nos.lol
- All public keys displayed as npub (NIP-19), stored as hex internally
