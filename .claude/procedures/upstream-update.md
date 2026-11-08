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
| Scene SMB size | `TsunamiSceneSmb.kt`, `core/ui/.../strings_tsunami_local.xml` | `DetermineBasalTsunami.kt`, `TsunamiPlugin.kt`, `SceneAction.kt`, `SceneSerializer.kt`, `ActionEditors.kt`, `SceneExecutor.kt` |
| Scene max IOB | `TsunamiSceneMaxIob.kt`, both `strings_tsunami_local.xml` | `DetermineBasalTsunami.kt`, `TsunamiPlugin.kt`, `SceneAction.kt`, `SceneSerializer.kt`, `ActionEditors.kt`, `SceneExecutor.kt`, `SceneListViewModel.kt`, `SceneWizardViewModel.kt`, `SceneWizardScreen.kt`, `SmbStep.kt` |

## The watch changes (branch `tsunami-watch`)

Tsunami and prebolus from the watch touch more shared files than the other local changes. Grep "Local change" as usual, and also check these:

- `wear/watchfacepush/src/wfs/template/watchface.xml` and the two preview pictures are ours and replace the upstream face. On a conflict keep ours. If upstream adds an embedded face or changes how faces are chosen, look at it again.
- `ComplicationData.tsunamiActiveUntil` must stay the **last** field. Protobuf numbers the fields by their order, so if upstream adds a field after it, move ours to the end again, or use `@ProtoNumber`. Otherwise the stored data on the watch cannot be read.
- `EventData.Preferences`: our fields go last and have defaults.
- Install the phone and the watch APK from the same build. They share the message format.

## The scene SMB size (branch `tsunami-scene-smb`)

The SMB action of a scene has an extra `smbPercent`. It changes upstream scene files, so expect conflicts there:

- `SceneAction.SmbToggle` keeps `smbPercent` with the default 100. If upstream changes the SMB action, keep the field and its default.
- `SceneSerializer`: we write `smbPercent` and read it with a default of 100, clamped to 10-100. Builds without it ignore the field, so scenes stay readable both ways.
- `DetermineBasalTsunami`: the SMB is scaled, but the insulin requirement is lowered by the **full** SMB, so the temp basal does not make up the difference. If upstream rewrites the SMB block, keep both parts.

## The scene max IOB (branch `tsunami-scene-smb`)

A scene can have a `SceneAction.MaxIob` with a percentage of the max IOB setting (0-100). It has no effect when the
scene starts. Tsunami reads it in every loop run:

- `TsunamiPlugin.applyMaxIOBConstraints` calls `TsunamiSceneMaxIob.apply` after the value from preferences and before
  the hard limit. It uses `setIfSmaller`, so it can only lower max IOB. If upstream rewrites this function, keep the call.
- `DetermineBasalTsunami` gets `sceneLimitsMaxIob` and skips the extra max IOB above 180 while it is true. A failure in
  `TsunamiHighBgMaxIobWiringTest` (the scene test) means this line was lost.
- The wizard shows it on the SMB step. If upstream changes the wizard steps, keep the second toggle there.
- Builds without it skip the unknown `max_iob` type, so scenes stay readable both ways.
