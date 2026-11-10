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
| Meal detector | `TsunamiMealDetector.kt`, `TsunamiMealDetectorRunner.kt`, `TsunamiLocalKeys.kt`, `strings_tsunami_local.xml` | `TsunamiPlugin.kt`, `DetermineBasalTsunami.kt` (reason text only), `NotificationId.kt` |

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

## The meal detector (branch `tsunami-meal-detector`)

Off by default. In auto mode it starts a Tsunami session by itself when it sees a fast daytime rise:

- `TsunamiPlugin.invoke` calls `mealDetectorRunner.run` just **before** it reads the running session. Keep that order,
  or a session the detector starts is only used five minutes later. The SMB size passed to `determine_basal` is the
  smaller of the scene and the detector.
- `NotificationId.TSUNAMI_MEAL_DETECTOR` must stay at the end of the enum. The system notification id comes from the
  ordinal.
- The detector only changes sessions it started itself. It finds them by the start time it keeps in preferences.
- A detected session has its own length (`MealDetectorSessionMinutes`), not `IntKey.TsuDefaultDuration`. The detector
  starts later in the meal than a session the user starts before eating.
- The only wait between starts in auto mode is after the user stops a detected session (`MealDetectorStopWaitMinutes`).
  A stop is a detected session that is gone before its planned end, which is kept in preferences. If an upstream
  change ends Tsunami sessions in a new way (for example on a profile switch), that would count as a stop.
- With `MealDetectorOwnSettings` on, a detected session uses its own max SMB size, activity target and insulin required
  in place of `TsuSMBCap`, `TsuActivityTarget` and `TsuInsReqPCT`. This is the `tsunamiModeID == 2` branch of
  `TsunamiPlugin.invoke`. If upstream adds another Tsunami session setting there, decide if detected sessions need
  their own copy of it.
- No start while a temp target with the reason Hypo or Activity runs, or for 60 min after it ended. A Hypo target
  ends a detected session. Scenes and the watch presets set these reasons too, so the rule finds them by the reason
  only. If upstream adds a new reason or changes how a scene sets its temp target, check that the rule still sees it.
- No start while a running scene sets the SMB size below 100% (`TsunamiSceneSmb.smbPercent`). This is how the user
  eats a small snack without a start: a scene with a smaller SMB size and nothing else. `TsunamiPlugin.invoke` reads
  the scene SMB size once and gives it to both the detector and `determine_basal`. Keep it that way.
- The detector settings have their own menu next to the Tsunami and Wave menus, not inside the Tsunami menu: the
  settings UI does not draw a third menu level.
