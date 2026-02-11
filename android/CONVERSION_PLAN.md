# BitChat: Swift → Kotlin/Android Conversion Plan

## Architecture Overview (from Swift codebase analysis)

### Current Swift Codebase Structure
```
bitchat/
├── Models/          → BitchatPacket, BitchatMessage, BitchatPeer, PeerID
├── Protocols/       → BinaryProtocol (wire format), BitchatProtocol (message types)
├── Noise/           → NoiseProtocol, NoiseSession, NoiseSessionManager
├── Services/
│   ├── BLE/         → BLEService (4592 lines - main transport)
│   ├── Transport.swift       → Transport protocol interface
│   ├── TransportConfig.swift → All configuration constants
│   ├── NostrTransport.swift  → Nostr relay transport
│   ├── MessageRouter.swift   → Multi-transport routing
│   ├── NoiseEncryptionService.swift → Encryption orchestration
│   └── ... (20+ service files)
├── ViewModels/      → ChatViewModel (3890 lines - main coordinator)
├── Views/           → SwiftUI views
├── Sync/            → Gossip sync (GCS filters)
├── Utils/           → Compression, validation, formatting
├── Identity/        → Secure identity management
└── Features/        → Feature-specific modules
```

### Key Architecture Patterns
1. **Transport Protocol** - Abstract interface (BLEService, NostrTransport implement it)
2. **BitchatDelegate** - Event-driven callbacks from transport to ViewModel
3. **MessageRouter** - Routes messages across multiple transports
4. **Noise Protocol** - XX handshake with Curve25519 + ChaCha20-Poly1305 + SHA-256
5. **BinaryProtocol** - Compact wire format with v1/v2 headers, compression, padding
6. **PeerID** - Multi-format peer identification (short 16-hex, full 64-hex Noise key)

## Android Project Structure
```
android/app/src/main/java/chat/bitchat/
├── model/
│   ├── PeerID.kt
│   ├── BitchatPacket.kt
│   ├── BitchatMessage.kt
│   ├── BitchatPeer.kt
│   ├── MessageType.kt
│   ├── NoisePayloadType.kt
│   ├── DeliveryStatus.kt
│   └── ReadReceipt.kt
├── protocol/
│   ├── BinaryProtocol.kt
│   ├── MessagePadding.kt
│   └── CompressionUtil.kt
├── noise/
│   ├── NoiseCipherState.kt
│   ├── NoiseSymmetricState.kt
│   ├── NoiseHandshakeState.kt
│   ├── NoiseSession.kt
│   ├── NoiseSessionManager.kt
│   ├── NoiseEncryptionService.kt
│   └── NoiseRateLimiter.kt
├── transport/
│   ├── Transport.kt           (interface)
│   ├── TransportConfig.kt
│   ├── BleTransport.kt
│   ├── WifiAwareTransport.kt  (NEW)
│   ├── TransportSelector.kt   (NEW - intelligent routing)
│   └── MessageRouter.kt
├── service/
│   ├── MeshForegroundService.kt
│   ├── KeystoreManager.kt
│   ├── IdentityManager.kt
│   ├── CommandProcessor.kt
│   ├── MessageDeduplicationService.kt
│   └── UnifiedPeerService.kt
├── sync/
│   ├── GossipSyncManager.kt
│   └── GCSFilter.kt
├── nostr/
│   ├── NostrTransport.kt
│   ├── NostrRelayManager.kt
│   └── NostrProtocol.kt
├── ui/
│   ├── theme/
│   ├── screens/
│   ├── components/
│   └── ChatViewModel.kt
└── BitchatApp.kt
```

## Conversion Phases
1. ✅ Phase 1: Project scaffold (Gradle, manifest, dependencies)
2. ✅ Phase 2: Core models (PeerID, BitchatPacket, etc.)
3. ✅ Phase 3: Binary protocol (wire-compatible encoding/decoding)
4. ✅ Phase 4: Noise protocol engine (crypto primitives)
5. ✅ Phase 5: Transport interface + BLE transport
6. ✅ Phase 6: Wi-Fi Aware transport (NEW)
7. Phase 7: Transport selector + MessageRouter
8. Phase 8: Services (identity, dedup, sync, commands)
9. Phase 9: Nostr transport
10. Phase 10: UI layer (Jetpack Compose)
11. Phase 11: Foreground service + permissions
