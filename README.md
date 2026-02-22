# CraftForge 🛠️

![Android](https://img.shields.io/badge/Android-12.0%2B-3DDC84?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?style=flat-square&logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-UI-4285F4?style=flat-square&logo=android)
![Root Required](https://img.shields.io/badge/Root-Required-red?style=flat-square)

**CraftForge** is an advanced, modern Android system tweaking and kernel optimization tool...

**CraftForge** is an advanced, modern Android system tweaking and kernel optimization tool. Built entirely with Kotlin and Jetpack Compose, it acts as a "Swiss Army knife" for power users looking to squeeze maximum performance or battery life out of their devices.

## ✨ Features

- **Smart Probing Engine**: Dynamically detects supported kernel features (EAS, MGLRU, Adreno Idler) without hardcoding paths, making it universally compatible across different kernels (v4.9 to v6.1+).
- **CPU Engineering**: Deep control over CPU Governors, Frequencies, Energy Aware Scheduling (EAS), Multicore Power Savings, and Governor Tunables.
- **GPU Tuning**: Supports Qualcomm Adreno (and detects unsupported/Mali GPUs). Adjust frequencies, Adreno Boost, and Adreno Idler states.
- **Advanced Memory & I/O**: Manage ZRAM algorithms, Swappiness, Multi-Gen LRU (MGLRU), Dirty Cache Ratios, and Storage I/O Queues (supports both UFS and eMMC).
- **Network Optimization**: Fine-tune TCP Congestion Algorithms (BBR, Westwood), TCP Fast Open, and IPv6 settings.
- **Silent Boot Service**: A lightweight, invisible background service that seamlessly reapplies your custom configurations on every device reboot.

## ⚠️ Disclaimer

**Root access (Magisk / KernelSU / APatch) is strictly required.** Modifying kernel parameters can lead to system instability, bootloops, or hardware degradation if used incorrectly. The developers are not responsible for any damage caused to your device. Use at your own risk!

## 🚀 Installation

1. Download the latest `.apk` from the [Releases](#) tab.
2. Install the app on your rooted Android device.
3. Grant Superuser (Root) permissions when prompted.
4. Tweak your system and enjoy!

## 🛠 Tech Stack

- **UI**: Jetpack Compose, Material Design 3
- **Language**: Kotlin
- **Asynchrony**: Kotlin Coroutines & Dispatchers
- **Architecture**: Smart Feature Probing & Background Services

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to check the [issues page](#).

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.