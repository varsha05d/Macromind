# MacroMind

MacroMind is an Android food health analyzer written in Kotlin. It scans food packaging, crops the relevant label area, runs OCR locally, and analyzes the extracted text with on-device machine learning to produce a health verdict, category match, additive warnings, and explanations. The app is designed to work fully offline.

## Highlights

- Fully offline Android app with no server dependency.
- Camera and gallery input for food packet scans.
- OCR with ML Kit text recognition.
- On-device inference with TensorFlow Lite.
- Ingredient and nutrition parsing from cropped label text.
- Local additive detection using a built-in JSON database.
- Scan history stored locally with Room.
- Custom crop UI and results flow for a guided scanning experience.

## Tech Stack

- Kotlin
- Android SDK
- CameraX
- ML Kit Text Recognition
- TensorFlow Lite
- Room
- Gson
- Coroutines

## Project Structure

- `app/src/main/java/com/macromind/foodscanner/` - app source code
- `app/src/main/res/` - layouts, drawables, animations, and UI resources
- `app/src/main/assets/` - models, vocabulary, metadata, and additive database
- `app/src/main/AndroidManifest.xml` - permissions and activity declarations

## Requirements

- Android Studio
- JDK 11
- Android device or emulator running Android 7.0+ (API 24+)

## Setup

1. Open the project in Android Studio.
2. Let Gradle sync finish.
3. Make sure the asset files in `app/src/main/assets/` are present.
4. Connect an Android device or start an emulator.

## Build and Run

1. Click Run in Android Studio.
2. Grant camera and storage/gallery permissions if prompted.
3. Scan a food packet or choose an image from the gallery.
4. Crop the ingredient or nutrition panel and continue through the analysis flow.

## App Info

- App name: MacroMind
- Package name: `com.macromind.foodscanner`
- Minimum SDK: 24
- Target SDK: 35
- Version: 5.0

## Notes

- The app is intended to run offline once the models and assets are present.
- The repo includes local models, metadata, and additive data under `app/src/main/assets/`.
- Scan history and session data are managed on-device.
