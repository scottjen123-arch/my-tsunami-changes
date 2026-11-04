# Updating from upstream Tsunami

This repo is a personal fork. Upstream is `piecycle/TsunamiNew`, branch `tsunami`. Local work stays on top of it.

## Rules for local changes

Keep local changes easy to merge:

- Put new logic in a **new file**. Upstream cannot conflict with a file it does not have.
- New settings go in `TsunamiLocalKeys.kt`, not in `BooleanKey` or `DoubleKey`.
- New text goes in `strings_tsunami_local.xml`, not in `strings.xml`.
- In an upstream file, change as few lines as possible and mark each one with `// Local change`.
- If a lost line would make a feature stop with no visible sign, add a test that runs the real code.

## Steps

Do this on a new branch, so `tsunami` stays usable if the merge goes badly.

1. Add the upstream remote, once only:

   ```bash
   git remote add upstream https://github.com/piecycle/TsunamiNew.git
   ```

2. Get the new commits and look at them. Read every change to `DetermineBasalTsunami.kt` and `TsunamiSafety.kt`,
   because they decide dosing:

   ```bash
   git fetch upstream
   git log --oneline HEAD..upstream/tsunami
   git diff HEAD...upstream/tsunami -- plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/tsunami/
   ```

3. Merge:

   ```bash
   git merge upstream/tsunami
   ```

4. If there is a conflict, keep the upstream change and add the local lines back. Find them with:

   ```bash
   git grep -n "Local change" -- "*.kt"
   ```

5. Run the tests. Use `./gradlew.bat` on Windows. Redirect the output to a log file and grep it, because a pipe hides
   a failed result:

   ```bash
   ./gradlew :plugins:aps:testAndroidHostTest --no-daemon > build.log 2>&1
   ```

   A failure in `TsunamiHighBgMaxIobWiringTest` means the merge lost a line of the extra max IOB feature.

6. Check that the settings still show in Tsunami mode settings, then build the APK. Do not install it automatically.

## Local changes to keep

`git log upstream/tsunami..HEAD` is the full list. These are the ones with hooks in upstream files:

| Feature | Own files | Upstream files touched |
|---|---|---|
| Extra max IOB above 180 | `TsunamiHighBgMaxIob.kt`, `TsunamiLocalKeys.kt` | `DetermineBasalTsunami.kt`, `TsunamiPlugin.kt` |
| Max IOB follows profile % | `TsunamiMaxIobScaling.kt` | `TsunamiPlugin.kt` |
| SMB cap uses real profile % | `TsunamiSmbCapScaling.kt` | `TsunamiPlugin.kt` |
