# FineVolume 🔊

**FineVolume** is an advanced Android volume control application that provides ultra-granular volume adjustment for devices with coarse volume steps.

## ✨ Features

- **Custom Step Count**: Choose from 30, 50, 100, 150, 200, or set any custom step count.
- **Low-Range Fine Control Curve**: Exponential gain mapping (`rawRatio ^ 1.7`) for ultra-fine adjustments in quiet environments (0–35% volume).
- **Slim Right-Side Floating Overlay**: Compact vertical volume bar (`44dp` width × `210dp` height) positioned right next to physical volume keys with smooth animations and auto-dismiss on outside tap.
- **Interactive System Sound Mixer Button**: Tap the speaker icon at the bottom of the overlay to quickly open Android's native sound panel for alarm, ring, and notification levels.
- **Continuous Hold-to-Scroll**: Tap for 1-step adjustments or hold volume up/down for smooth continuous scrolling (10 steps per second).
- **Per-Device Volume Memory**: Automatically remembers and restores separate fine volume levels for Bluetooth headphones, wired headsets, and phone speakers.
- **Lockscreen & Screen-Off Sync**: Keeps volume levels in sync when screen is locked or when using Bluetooth headphone buttons, with feedback-loop prevention.

## 🛠️ Built With

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose & Material 3
- **Audio Engine**: Android `AudioManager` & `LoudnessEnhancer` AudioEffect API
- **Key Interception**: Android `AccessibilityService`

## 🚀 Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/Bobbite/FineVolume.git
   ```
2. Open the project in Android Studio.
3. Build & run on your Android device (Android 8.0+ / API 26+).

## 📄 License

MIT License
