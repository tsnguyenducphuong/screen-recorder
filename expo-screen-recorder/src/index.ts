// Reexport the native module. On web, it will be resolved to ExpoScreenRecorderModule.web.ts
// and on native platforms to ExpoScreenRecorderModule.ts
// export { default } from './ExpoScreenRecorderModule';
// export * from './ExpoScreenRecorder.types';


import ExpoScreenRecorderModule from './ExpoScreenRecorderModule';
import type { RecordingOptions, RecordingResult } from './ExpoScreenRecorder.types';

export async function isAvailableAsync(): Promise<boolean> {
  return await ExpoScreenRecorderModule.isAvailableAsync();
}

export async function startRecordingAsync(options: RecordingOptions = {}): Promise<void> {
  return await ExpoScreenRecorderModule.startRecordingAsync(options);
}

export async function stopRecordingAsync(): Promise<RecordingResult> {
  return await ExpoScreenRecorderModule.stopRecordingAsync();
}

export type { RecordingOptions, RecordingResult };