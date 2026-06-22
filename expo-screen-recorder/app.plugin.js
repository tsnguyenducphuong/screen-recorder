const { withAndroidManifest, createRunOncePlugin } = require('@expo/config-plugins');

function withScreenRecorderService(config) {
  return withAndroidManifest(config, async (config) => {
    let androidManifest = config.modResults;

    if (!androidManifest.manifest['uses-permission']) {
      androidManifest.manifest['uses-permission'] = [];
    }
    
    // Explicitly add all FGS permissions required by target SDK 36
    const permissionsToAdd = [
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
      'android.permission.FOREGROUND_SERVICE_MICROPHONE'
    ];

    permissionsToAdd.forEach(permName => {
      const exists = androidManifest.manifest['uses-permission'].some(
        p => p['$']['android:name'] === permName
      );
      if (!exists) {
        androidManifest.manifest['uses-permission'].push({ '$': { 'android:name': permName } });
      }
    });

    const mainApplication = androidManifest.manifest.application[0];
    if (!mainApplication.service) {
      mainApplication.service = [];
    }

    const serviceName = 'expo.modules.screenrecorder.ScreenCaptureService';
    const serviceIndex = mainApplication.service.findIndex(s => s['$']['android:name'] === serviceName);

    const serviceDefinition = {
      '$': {
        'android:name': serviceName,
        'android:enabled': 'true',
        'android:exported': 'false',
        // Pipes combined system privileges explicitly 
        'android:foregroundServiceType': 'mediaProjection|microphone'
      }
    };

    if (serviceIndex > -1) {
      mainApplication.service[serviceIndex] = serviceDefinition;
    } else {
      mainApplication.service.push(serviceDefinition);
    }

    return config;
  });
}

module.exports = createRunOncePlugin(withScreenRecorderService, 'expo-screen-recorder', '1.2.8');