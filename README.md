# Glance

Glance is a privacy-focused, watch-only Bitcoin wallet for Android. Monitor single-signature HD wallets without importing private wallet keys.

<p>
  <a href="https://zapstore.dev/apps/app.glance.wallet">
    <img src=".github/assets/get-it-on-zapstore.svg" alt="Get it on Zapstore" height="50" />
  </a>
  <a href="https://github.com/tellstone/glance-android">
    <img src=".github/assets/get-it-on-github.png" alt="View Glance on GitHub" height="50" />
  </a>
</p>

> **Beta software:** Use only public or disposable wallet data while testing, and independently verify wallet information before acting on it.

## Features

- Watch-only BIP44, BIP49, BIP84, and BIP86 wallets, descriptors, and fixed mainnet addresses.
- Monitor balances, transactions, UTXOs, and receiving addresses.
- Tor-routed Electrum, Esplora, and fiat requests by default.
- Encrypted local storage with PIN and biometric protection.

Glance deliberately does not import private wallet keys, sign or broadcast transactions, support multisig or BIP47, or provide production testnet/signet support. The optional duress profile is a documented exception: Glance generates and stores a separate decoy seed so the isolated decoy wallet can show real receive addresses. Glance cannot spend from it, and users should not send funds to those addresses.

Stealth mode only disguises the normal launcher entry. It is not an authentication or forensic boundary: the package and exported activity can still be discovered or launched directly, and the PIN remains the security boundary. Screenshot blocking is enabled by default but can be disabled; PINs, backup passphrases, and the optional decoy phrase are immutable JVM strings and cannot be reliably zeroized from memory.

## Build from source

Requirements: JDK 21 and Android SDK Platform 37 with Build Tools 36.0.0.

On Windows:

```powershell
.\gradlew.bat build lint
```

## License

Glance is licensed under the [MIT License](LICENSE).
