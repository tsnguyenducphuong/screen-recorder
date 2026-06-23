import {
  ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
} from '@expo/config-plugins';

/**
 * Expo config plugin — adds foreground service permissions and the
 * ScreenCaptureService declaration required by the screen recorder module
 * when targeting Android SDK 36.
 *
 * In TypeScript plugins, ConfigPlugin<void> means the plugin takes no
 * options. If you later want to accept options (e.g. a custom service
 * name), change `void` to your options interface type.
 */
const withScreenRecorderService: ConfigPlugin<void> = (config) => {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;

    // ── 1. Permissions ─────────────────────────────────────────────────────
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

    // ── 2. Service declaration ──────────────────────────────────────────────
    const mainApplication = androidManifest.manifest.application![0];

    if (!mainApplication.service) {
      mainApplication.service = [];
    }

    const serviceName =
      'expo.modules.screenrecorder.ScreenCaptureService';

    // ManifestServiceAttributes requires 'android:enabled' and
    // 'android:exported' to be the StringBoolean literal union
    // ('true' | 'false'), not the broad string type. Using `as const`
    // on the object narrows all string values to their literal types,
    // satisfying the ManifestService type without needing an explicit cast
    // on every individual property.
    //
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

export default createRunOncePlugin(withScreenRecorderService, 'expo-screen-recorder', '1.3.0');