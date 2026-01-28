# Unfiltered

A photo-sharing social app built on the Nostr protocol for Android. Share photos with your followers without algorithms, ads, or centralized control.

## What is Unfiltered?

Unfiltered is an Instagram-like photo sharing app that runs on Nostr - a decentralized social protocol. Your account is a cryptographic key pair that you own, not an account on someone else's server.

### Why Nostr?

- **You own your identity** - Your account is a key pair. No email, phone number, or personal info required.
- **No algorithms** - See posts from people you follow, in chronological order.
- **No ads** - The protocol doesn't have a business model that requires selling your attention.
- **Censorship resistant** - Your content is distributed across multiple relays. No single company can delete your account.
- **Interoperable** - Use your same identity across any Nostr app (Damus, Primal, Amethyst, etc.)

## Features

- **Photo Sharing** - Post photos with captions to your followers
- **Feed** - View photos from accounts you follow
- **Profiles** - Customizable profile with avatar, banner, display name, and bio
- **Search** - Find users by name, npub, or NIP-05 identifier
- **Reactions** - Like posts from other users
- **Zaps** - Send Bitcoin tips to creators via Lightning Network
- **NIP-05 Verification** - Verify your identity with a domain you control
- **Amber Integration** - Secure key management using the Amber signer app

## Getting Started

### Installation

Download the APK from the releases page or build from source.

### Creating an Account

1. **Recommended**: Install [Amber](https://github.com/greenart7c3/Amber) for secure key management, then sign in with Amber
2. **Alternative**: Import an existing nsec (private key) or generate a new account

### Backing Up Your Keys

Your private key (nsec) is the **only** way to access your account. If you lose it, your account is gone forever. Back it up securely!

---

## Technical Documentation

### Technology Stack

| Component | Technology |
|-----------|------------|
| Platform | Native Android |
| Language | Kotlin |
| UI Framework | Jetpack Compose |
| Architecture | MVVM with StateFlow |
| Nostr Library | rust-nostr Kotlin bindings |
| Image Hosting | Blossom-compatible servers |
| Authentication | Amber (NIP-55) / nsec fallback |

### Requirements

- Android 8.0+ (API 26)
- JDK 17-24 for building

### Building from Source

```bash
# Clone the repository
git clone https://github.com/dmcarrington/unfiltered.git
cd unfilftered

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test

# Lint check
./gradlew lint
```

### Project Structure

```
app/src/main/java/com/nostr/unfiltered/
├── di/                 # Dependency injection (Hilt)
├── nostr/              # Nostr protocol implementation
│   ├── models/         # Data models (PhotoPost, UserMetadata, etc.)
│   ├── BlossomClient   # Image upload to Blossom servers
│   ├── KeyManager      # Key storage and Amber integration
│   ├── Nip05Service    # NIP-05 verification
│   ├── NostrClient     # Relay connections and subscriptions
│   ├── NwcService      # Nostr Wallet Connect
│   ├── SearchService   # NIP-50 search
│   └── ZapManager      # Lightning zaps
├── repository/         # Data layer
├── ui/                 # Compose screens and components
│   ├── components/     # Reusable UI components
│   └── screens/        # App screens (Feed, Profile, Auth, etc.)
└── viewmodel/          # ViewModels with business logic
```

### Nostr Protocol Support

#### Supported NIPs

| NIP | Name | Description |
|-----|------|-------------|
| NIP-01 | Basic Protocol | Events, relays, subscriptions |
| NIP-02 | Contact List | Follow/follower lists |
| NIP-04 | Encrypted DMs | Used with Amber for NWC encryption |
| NIP-05 | DNS Verification | user@domain.com identity verification |
| NIP-19 | Bech32 Encoding | npub, nsec, nprofile encoding |
| NIP-25 | Reactions | Like/reaction events |
| NIP-47 | Nostr Wallet Connect | Lightning wallet integration for zaps |
| NIP-50 | Search | Relay-based user search |
| NIP-55 | Android Signer | Amber app integration |
| NIP-57 | Zaps | Lightning tips to users |
| NIP-68 | Picture Events | Photo-first feed format |
| NIP-94 | File Metadata | Image metadata (dimensions, blurhash, etc.) |

#### Event Kinds Used

| Kind | Purpose | NIP |
|------|---------|-----|
| 0 | User metadata/profile | NIP-01 |
| 1 | Text notes | NIP-01 |
| 3 | Contact list (follows) | NIP-02 |
| 7 | Reactions (likes) | NIP-25 |
| 20 | Picture posts | NIP-68 |
| 9734 | Zap request | NIP-57 |
| 23194 | NWC request | NIP-47 |
| 24242 | Blossom auth | Blossom |

### Default Relays

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://nos.lol`

### Image Hosting

Photos are uploaded to Blossom-compatible servers using authenticated uploads (kind 24242 events). Default server: `nostr.build`

---

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## License

[Add license information here]

## Acknowledgments

- [rust-nostr](https://github.com/rust-nostr/nostr) - Nostr protocol implementation
- [Amber](https://github.com/greenart7c3/Amber) - Android Nostr signer
- The Nostr community for building an open protocol
