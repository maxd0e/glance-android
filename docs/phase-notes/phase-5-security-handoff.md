# Phase 5 security handoff

Phase 2 introduced `DatabaseKeyProvider` and its Android Keystore-backed implementation in
`:core-data` because the Phase 2 acceptance criterion requires a SQLCipher key that is never
stored in plaintext.

During Phase 5, make `:core-security` the owner of the Keystore implementation or delegate to it
from `:core-data`. Preserve the `DatabaseKeyProvider` contract and do not move the key into
DataStore, PIN storage, or the real/decoy profile data. The real and decoy SQLCipher databases
must continue to receive independently generated, Keystore-wrapped keys.
