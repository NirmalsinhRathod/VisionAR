# VisionAR 🔮

VisionAR is a powerful **React Native** application that brings Augmented Reality to life on mobile devices. Built with native AR frameworks (ARCore for Android, ARKit for iOS), it enables users to place and interact with virtual text and images in real-world environments. This app showcases the seamless integration of native AR capabilities with React Native's cross-platform architecture.

## Features ⭐️

- **AR Text Rendering**: Display dynamic, customizable text in 3D augmented reality space
- **Interactive Text Rotation**: Drag placed text to rotate it horizontally with smooth gesture controls
- **AR Image Placement**: Place images from local gallery or remote URLs in AR environment
- **Multi-Placement Support**: Add multiple text or image objects in a single AR session
- **Real-time Surface Detection**: Automatic detection of horizontal planes for accurate AR placement
- **Native Performance**: Leveraging ARCore (Android) and ARKit (iOS) for optimal AR experience
- **Modern UI/UX**: Beautiful, intuitive interface with loading states and visual feedback
- **Camera Integration**: Built-in camera access with proper permission handling
- **Gallery Integration**: Pick images directly from device gallery using React Native Image Picker

## Demo ✨

![](Preview/ARDemo.gif)

## Platform Support 📱

| Platform       | AR Text | AR Image | AR Framework |
| -------------- | ------- | -------- | ------------ |
| 🤖 **Android** | ✅      | ✅       | ARCore       |
| 🍎 **iOS**     | ✅      | ✅       | ARKit        |

## Technical Stack 🛠

- **React Native** + TypeScript - Cross-platform mobile framework
- **ARCore** (Android) / **ARKit** (iOS) - Native AR frameworks
- **OpenGL ES 2.0** - Graphics rendering
- **Native Modules** - Custom ViewManagers & Bridge integration
- **React Native APIs** - Animated, Image Picker, Permissions
- **AR Components** - Session Management, Plane Detection, Anchor System, Billboard Renderer

## AR Gesture Controls 👆

| Gesture               | AR Text                  | AR Image        |
| --------------------- | ------------------------ | --------------- |
| Tap (on surface)      | Place new text           | Place new image |
| Drag (on placed text) | Rotate horizontally      | N/A             |
| Multiple Taps         | Place multiple instances | Place multiple  |
| Pinch                 | N/A (planned)            | Scale (planned) |

## Installation ⚡️

### Prerequisites

- Node.js (v16 or higher)
- React Native development environment
- Android Studio (for Android)
- Xcode (for iOS - macOS only)
- Physical device with AR support (ARCore/ARKit compatible)

### Setup Steps

1. **Clone the repository**

```bash
git clone https://github.com/NirmalsinhRathod/VisionAR.git
cd VisionAR
```

2. **Install dependencies**

```bash
# Using npm
npm install

# OR using Yarn
yarn install
```

3. **Android Setup**

```bash
# No additional setup required
# ARCore is included in the project
```

4. **iOS Setup** (macOS only)

```bash
# Install CocoaPods dependencies
bundle install
cd ios
bundle exec pod install
cd ..
```

5. **Run the app**

```bash
# Start Metro
npm start

# Android
npm run android

# iOS
npm run ios
```

## Troubleshooting 🔧

- **Camera Permission**: Grant camera permissions in device settings
- **AR Not Working**: Ensure device supports ARCore (Android 7.0+) and Google Play Services for AR is installed
- **Surface Detection**: Move device slowly, ensure good lighting, point at flat textured surfaces
- **Text Rotation**: Touch directly on placed text and drag horizontally (left/right)

## Project Structure 📁

```
VisionAR/
├── android/
│   └── app/src/main/java/com/visionar/
│       ├── NativeARTextViewManager.java    # AR Text native module
│       ├── NativeARImageViewManager.java   # AR Image native module
│       └── NativeARTextPackage.java        # Package registration
├── ios/
│   └── NativeARTextDemo/
│       ├── ARTextView.swift                # iOS AR Text (in progress)
│       └── NativeARTextViewManager.swift
├── App.tsx                                 # Main application component
├── ARTextView.tsx                          # AR Text React component
├── ARImageView.tsx                         # AR Image React component
└── package.json
```

## Key Implementation Details 🔍

- **Custom OpenGL Renderer** - Camera feed and AR content rendering
- **Anchor & Rotation System** - 3D position tracking with rotation angles
- **Hit Testing** - 3D to 2D projection for touch detection
- **Matrix Management** - Proper initialization to ensure reliable rotation
- **Native-JS Bridge** - Event communication for AR state updates
- **Performance** - Continuous rendering, efficient texture management, optimized hit testing

## Permissions Required 📋

### Android

- `CAMERA` - Required for AR camera feed
- `READ_MEDIA_IMAGES` (Android 13+) - For gallery image selection
- `READ_EXTERNAL_STORAGE` (Android 12 and below) - For gallery access

### iOS

- `NSCameraUsageDescription` - AR camera access
- `NSPhotoLibraryUsageDescription` - Gallery access

## Author 🙋🏻‍♂️

- [@NirmalsinhRathod](https://github.com/NirmalsinhRathod) 🧑🏻‍💻

## Resources 📚

### React Native

- [React Native Documentation](https://reactnative.dev)
- [React Native Environment Setup](https://reactnative.dev/docs/environment-setup)

### AR Frameworks

- [ARCore Overview](https://developers.google.com/ar) - Google's AR platform
- [ARCore Android Development](https://developers.google.com/ar/develop/java/quickstart)
- [ARKit Documentation](https://developer.apple.com/augmented-reality/arkit/) - Apple's AR framework

### OpenGL ES

- [OpenGL ES 2.0 Documentation](https://www.khronos.org/opengles/)
- [OpenGL ES Best Practices](https://developer.android.com/guide/topics/graphics/opengl)

## License 📄

This project is available for educational and personal use.

---

Built with ❤️ using React Native and ARCore
