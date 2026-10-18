# Releasing

1. Update `CHANGELOG.md` and the versions in `android/app/build.gradle.kts` (`versionName`, `versionCode`) and `esp32/components/phonenet/idf_component.yml`.
2. `cd android && ./gradlew test assembleDebug` and `python3 -m unittest discover -s tools -p "test_*.py"` must pass.
3. Commit, then `git tag vX.Y.Z && git push origin main --tags`.
4. The `Release` workflow builds the APK and attaches it to a GitHub release (debug-signed; this is an experimental project).
5. Optional: publish the component to the ESP-IDF Component Registry from `esp32/components/phonenet` after setting the repository URL in `idf_component.yml`.
