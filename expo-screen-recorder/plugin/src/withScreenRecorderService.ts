import {
  ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
  withInfoPlist,
} from '@expo/config-plugins';

const pkg = require('expo-turbo-screen-recorder/package.json');

// ---------------------------------------------------------------------------
// Plugin options
// ---------------------------------------------------------------------------

interface ScreenRecorderPluginOptions {
  /**
   * iOS only — sets NSMicrophoneUsageDescription in Info.plist.
   * Required by Apple even when the user does not enable audio, because
   * ReplayKit internally references the microphone API.
   * App Store will reject the binary with ITMS-90683 if this is missing.
   * Defaults to a generic description if not supplied.
   */
  microphonePermission?: string;

  /**
   * iOS only — sets NSPhotoLibraryAddUsageDescription in Info.plist.
   * Required if you save recordings to the photo library.
   * Optional — omit if you only write to the app cache directory.
   */
  photoLibraryAddPermission?: string;
}

// ---------------------------------------------------------------------------
// Android — manifest permissions + service declaration
// ---------------------------------------------------------------------------

const withAndroidScreenRecorder: ConfigPlugin<ScreenRecorderPluginOptions> = (
  config
) => {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;

    // ── 1. Permissions ───────────────────────────────────────────────────────
    if (!androidManifest.manifest['uses-permission']) {
      androidManifest.manifest['uses-permission'] = [];
    }

    // All three foreground-service permission types are required on SDK 36+:
    //   FOREGROUND_SERVICE                  — base permission for any FGS
    //   FOREGROUND_SERVICE_MEDIA_PROJECTION — required for screen capture
    //   FOREGROUND_SERVICE_MICROPHONE       — required for mic recording
    const permissionsToAdd = [
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
      'android.permission.FOREGROUND_SERVICE_MICROPHONE',
    ];

    permissionsToAdd.forEach((permName) => {
      const exists = androidManifest.manifest['uses-permission']!.some(
        (p) => p.$['android:name'] === permName
      );
      if (!exists) {
        androidManifest.manifest['uses-permission']!.push({
          $: { 'android:name': permName },
        });
      }
    });

    // ── 2. Service declaration ───────────────────────────────────────────────
    const mainApplication = androidManifest.manifest.application![0];
    if (!mainApplication.service) {
      mainApplication.service = [];
    }

    const serviceName = 'expo.modules.screenrecorder.ScreenCaptureService';

    // foregroundServiceType must list BOTH types because the service
    // simultaneously captures the screen (mediaProjection) and the mic
    // (microphone). Omitting either causes a runtime crash on SDK 34+.
    const serviceDefinition = {
      $: {
        'android:name': serviceName,
        'android:enabled': 'true',
        'android:exported': 'false',
        'android:foregroundServiceType': 'mediaProjection|microphone',
      },
    } as const;

    const serviceIndex = mainApplication.service.findIndex(
      (s) => s.$['android:name'] === serviceName
    );

    if (serviceIndex > -1) {
      mainApplication.service[serviceIndex] = serviceDefinition;
    } else {
      mainApplication.service.push(serviceDefinition);
    }

    return config;
  });
};

// ---------------------------------------------------------------------------
// iOS — Info.plist permission strings
// ---------------------------------------------------------------------------

const withIOSScreenRecorder: ConfigPlugin<ScreenRecorderPluginOptions> = (
  config,
  options
) => {
  return withInfoPlist(config, (config) => {
    const plist = config.modResults;

    // ── NSMicrophoneUsageDescription ────────────────────────────────────────
    // REQUIRED — Apple rejects with ITMS-90683 if this key is missing.
    // ReplayKit references the microphone API even when isMicrophoneEnabled
    // is false, so the key must be present regardless of audio usage.
    // Only add if not already set — lets the app override via app.json.
    if (!plist['NSMicrophoneUsageDescription']) {
      plist['NSMicrophoneUsageDescription'] =
        options?.microphonePermission ??
        '$(PRODUCT_NAME) uses the microphone to record audio during screen recordings.';
    }

    // ── NSPhotoLibraryAddUsageDescription ───────────────────────────────────
    // Required only if recordings are saved to the Photos library.
    // This plugin writes to the app cache directory, so this is optional.
    // Include it here so apps that want to save to Photos don't need a
    // separate plugin. Only added when the caller explicitly supplies it.
    if (
      options?.photoLibraryAddPermission &&
      !plist['NSPhotoLibraryAddUsageDescription']
    ) {
      plist['NSPhotoLibraryAddUsageDescription'] =
        options.photoLibraryAddPermission;
    }

    // ── NO RPScreenRecordingUsageDescription needed ─────────────────────────
    // RPScreenRecordingUsageDescription was relevant only for the deprecated
    // RPScreenRecorder.startRecording(withMicrophoneEnabled:handler:) API.
    // The modern startCapture(handler:completionHandler:) API used here does
    // NOT require this key — Apple shows its own system consent sheet instead.

    return config;
  });
};

// ---------------------------------------------------------------------------
// Combined plugin
// ---------------------------------------------------------------------------

const withScreenRecorder: ConfigPlugin<ScreenRecorderPluginOptions | void> = (
  config,
  options
) => {
  const opts: ScreenRecorderPluginOptions = options ?? {};

  // Apply Android manifest changes.
  config = withAndroidScreenRecorder(config, opts);

  // Apply iOS Info.plist changes.
  config = withIOSScreenRecorder(config, opts);

  return config;
};

export default createRunOncePlugin(
  withScreenRecorder,
  pkg.name,
  pkg.version
);