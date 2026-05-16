# MacroMind — Phase 4 Offline Integration Guide

## ✅ Project Accomplishments (Status Update: April 20, 2026)

The project has transitioned from a server-dependent prototype to a premium, fully offline Android application. Below is a summary of the major work completed:

### 1. Core Architecture: "The Offline Shift"
*   **Removed Flask Dependency**: Eliminated the need for a Python backend. All processing now happens on the Android device.
*   **TFLite Integration**: Successfully integrated `food_gap_model.tflite` (Category) and `food_health_model.tflite` (Health) for real-time inference.
*   **Kotlin Explainer**: Implemented a custom `KernelShapExplainer` in Kotlin to bring data-driven explanations to the offline app.

### 2. Intelligent Data Processing
*   **Offline Analyzer**: Created a unified orchestrator (`OfflineAnalyzer.kt`) that handles the sequence: OCR → Vectorization → Inference → SHAP → Additive detection.
*   **Ingredient & Additive Engine**: Ported complex regex-based additive detection from Python to native Kotlin (`IngredientParser.kt`).
*   **Dynamic Asset Loading**: Implemented `AssetLoader` to manage vocabulary, background SHAP values, and model configurations from JSON files.

### 3. Premium UI/UX (Strava-Inspired)
*   **Zero-G Dark Theme**: Overhauled the entire UI with a professional dark mode, high-quality gradients (`bg_card_gradient`), and custom badges.
*   **Motion Design**: Added staggered entrance animations (`slide_up_in`, `fade_in`) to all data-driven components to create a premium feel.
*   **Workflow Optimization**: Integrated instructional text into the capture interface and streamlined the "Capture → Crop → Analyze" flow.

### 4. Security & Mobile Readiness (NEW)
*   **Zero-Permission Architecture**: Removed `INTERNET` permission. The app is now legally "Offline-First" and doesn't request unused network access.
*   **Data Protection**: Disabled ADB backups (`allowBackup="false"`) to prevent local data extraction via USB.
*   **Android 15+ Compatibility**: Optimized for 16KB memory page alignment (required for Android 15/16) via `extractNativeLibs="false"`.
*   **Sideloading Support**: Configured `gradle.properties` to allow generated APKs to be shared and installed via file managers without "invalid package" errors.

### 5. Infrastructure Refinement
*   **Purged Retrofit**: Completely removed unused network libraries (Retrofit/Gson) to shrink APK size and remove supply chain risks.
*   **Dependency Alignment**: Updated to `compileSdk 36` to support the latest CameraX 1.6.0 hardware requirements.
*   **Clean Test Fragments**: Cleaned up test UIs to remove leftover boilerplate for removed dependencies.

---

## Overview

Phase 4 replaces the Flask server entirely.  
Everything runs **on-device** — no internet, no server, no Retrofit.

```
Scan → CropActivity → ResultsActivity → OfflineAnalyzer
                                              ↓
                          CategoryInference (TFLite)
                          HealthInference   (TFLite)
                          KernelShapExplainer (Kotlin math)
                          IngredientParser  (regex + DB)
                                              ↓
                              Results displayed instantly
```

---

## Step 1 — Convert Models (Run on your laptop)

```bash
cd MacroMind_Backend   # or wherever ModelConverter.py is
pip install tensorflow numpy
python ModelConverter.py
```

This creates `android_assets/` with all files needed.

**Expected output:**
```
android_assets/
  food_gap_model.tflite          ~500 KB
  food_health_model.tflite       ~200 KB
  vocab_GAP1.json               ~300 KB
  metadata_GAP1.json             ~10 KB
  health_model_metadata.json      ~5 KB
  additive_database.json         ~500 KB
  shap_background.json            ~2 KB
  model_config.json               ~1 KB
```

---

## Step 2 — Add Assets to Android Studio

1. In Android Studio, right-click `app/src/main` → **New → Directory** → name it `assets`
2. Copy **all 8 files** from `android_assets/` into `app/src/main/assets/`
3. Verify in the Project view: `app > src > main > assets > food_gap_model.tflite` etc.

---

## Step 3 — Add Dependencies to build.gradle.kts

Open `MacroMind/app/build.gradle.kts` and add inside `dependencies { }`:

```kotlin
// TensorFlow Lite — on-device ML inference
implementation("org.tensorflow:tensorflow-lite:2.14.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

// Coroutines (should already be present from Phase 3)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

**CRITICAL:** Remove any Retrofit/Gson lines. They will cause compilation errors in the "Offline-Only" architecture.

Click **Sync Now**.

---

## Step 4 — Add New Kotlin Files

Copy these files into:
`MacroMind/app/src/main/java/com/macromind/foodscanner/`

| File | Purpose |
|------|---------|
| `AssetLoader.kt` | Loads all JSON assets into memory |
| `TextVectorizer.kt` | Tokenizes ingredient text |
| `CategoryInference.kt` | Runs category TFLite model |
| `HealthInference.kt` | Runs health TFLite model |
| `KernelShapExplainer.kt` | Real KernelSHAP on Android |
| `IngredientParser.kt` | Detects additives (Kotlin port of Python) |
| `OfflineAnalyzer.kt` | Orchestrates full pipeline |
| `ResultsActivity.kt` | Displays results screen |

---

## Step 5 — Add Results Layout

Copy `activity_results.xml` into:
`MacroMind/app/src/main/res/layout/`

---

### Step 6 — Harden AndroidManifest.xml

1. **Remove** `<uses-permission android:name="android.permission.INTERNET" />` (Top of file).
2. **Update** the `<application>` tag for security and Android 15 compatibility:

```xml
<application
    android:allowBackup="false"
    android:extractNativeLibs="false"
    ... >

    <activity
        android:name=".ResultsActivity"
        android:exported="false"
        android:screenOrientation="portrait" />
</application>
```

---

## Step 7 — Update CropActivity.kt

Find the existing `sendToBackend()` stub and replace it:

```kotlin
// OLD — delete this:
private fun sendToBackend() {
    Log.d("MacroMind", "=== SENDING TO BACKEND ===")
    Toast.makeText(this, "Phase 4 will send this to backend", Toast.LENGTH_LONG).show()
    ScanSession.clear()
}

// NEW — replace with:
private fun sendToBackend() {
    val intent = Intent(this, ResultsActivity::class.java)
    startActivity(intent)
    finish()
}
```

Also add the import at the top of CropActivity.kt if not already present:
```kotlin
import android.content.Intent
```

---

## Step 8 — Build & Run

1. Connect your Android phone (or use emulator API 24+)
2. Click **Run ▶**
3. Scan a food packet → crop ingredients → crop nutrition → tap Analyze

**Subsequent scans:** Models already loaded, analysis runs in ~0.5–1 sec

---

## Step 9 — Allow Sideloading (APK Sharing)

If you want to share the APK via WhatsApp/Email without it saying "Invalid Package", add this to `gradle.properties`:

```properties
android.injected.testOnly=false
```

Then, use **Build > Clean Project** followed by **Build > Build APK(s)** before sharing.

---

## What You'll See on ResultsActivity

```
┌─────────────────────────────────┐
│ MacroMind          🔒 100% Offline │
│                                 │
│          ✅                     │
│        HEALTHY                  │
│      82% confidence             │
│  ████████████████░░░░           │
└─────────────────────────────────┘
  📦 Cereals · 91% match

  🔍 Why this verdict?
  Protein (▲35%) improved the health score by ~35%
  ████████████████████
  Fiber (▲28%) improved the health score by ~28%
  ████████████████
  Sugar (▼12%) reduced the health score by ~12%
              ████████

  ✅ No additives detected

  🥗 Nutrition (per 100g)
  Energy         457 kcal
  Protein        21.2 g
  Total Fat       17.5 g
  ...

  [🔄 Scan Another Product]
```

---

## Troubleshooting

### "Model file not found"
→ Confirm `food_gap_model.tflite` is in `app/src/main/assets/` (not a subfolder)

### "AssetLoader not initialized"
→ This shouldn't happen — OfflineAnalyzer calls `AssetLoader.init()` automatically.  
   If it does, call `AssetLoader.init(applicationContext)` in `Application.onCreate()`.

### SHAP values all zero
→ Usually means `shap_background.json` has wrong dimensions.  
   Check that `background_values` array length matches `health_input_size` in `model_config.json`.

### Category model crashes (dual input)
→ The TFLite dual-input call uses `runForMultipleInputsOutputs()`.  
   If it crashes, check `CategoryInference.kt` — you may need to swap input order  
   (try index 0=nutrition, 1=text) depending on how your model was built.

### "App not installed: Package appears to be invalid"
→ You are likely trying to install a "Test-Only" APK.
1. Add `android.injected.testOnly=false` to `gradle.properties`.
2. Clean and Rebuild.
3. Do not use the "Run" button to copy the APK; use the generated file in `build/outputs/apk/debug/`.

### Nutrition always null
→ NutritionExtractor is optimized for Indian/Western labels. If it's always null, check `NutritionExtractor.kt` logs to see if OCR is misreading common keywords like "Protein" or "Energy".


---

## Architecture After Phase 4

```
No network permissions needed (remove INTERNET from Manifest if desired)
No server to run
No API keys
Works on airplane mode
Works in rural areas with no data
Instant results (~0.5 sec after first launch)
SHAP explanations are real KernelSHAP values, not heuristics
```

---

## Next: Phase 5 (Polish)

- Better ResultsActivity UI (charts, animations)
- Share result as image
- Save scan history to local Room database
- Offline first-launch tutorial
- App icon + splash screen
