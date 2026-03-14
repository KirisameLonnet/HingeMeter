# Repository Guidelines

## Project Structure & Module Organization
- `app/` is the main Android module.
- Kotlin sources live in `app/src/main/java/com/example/hingemeter`.
- Android resources are under `app/src/main/res` (layouts, values, drawables, fonts).
- Static assets are in `app/src/main/assets`.
- Native code lives in `app/src/main/cpp` and is built via `app/src/main/cpp/CMakeLists.txt`.
- Tests are split between `app/src/test` (local JVM) and `app/src/androidTest` (instrumented).

## Build, Test, and Development Commands
- `./gradlew assembleDebug`: build a debug APK.
- `./gradlew installDebug`: install the debug build on a connected device/emulator.
- `./gradlew testDebugUnitTest`: run local JVM unit tests.
- `./gradlew connectedDebugAndroidTest`: run instrumented tests on a device.
- `./gradlew lint`: run Android Lint checks.

## Coding Style & Naming Conventions
- Kotlin: 4-space indentation, `camelCase` for methods/vars, `PascalCase` for classes.
- Android resources: `lower_snake_case` (e.g., `activity_main.xml`).
- C++: keep files in `app/src/main/cpp`, use `PascalCase` filenames and consistent `camelCase` symbols.
- Prefer small, focused classes and keep UI logic in `MainActivity`/`AngleView` with rendering in C++.

## Testing Guidelines
- Unit tests use JUnit (`app/src/test`); instrumented tests use AndroidX + Espresso (`app/src/androidTest`).
- Name tests `*Test` and keep them next to the corresponding package structure.
- Add or update tests when changing angle computation, rendering, or UI behavior.

## Commit & Pull Request Guidelines
- The Git history does not show a strict commit convention; keep messages short and imperative (e.g., "Add angle smoothing").
- PRs should include a brief description, linked issue (if any), and screenshots or recordings for UI changes.

## Configuration Notes
- `local.properties` is developer-specific (Android SDK path); do not commit changes.
- If touching native code, keep `CMakeLists.txt` in sync with new or renamed files.
