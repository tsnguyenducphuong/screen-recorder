// import { NativeModule, requireNativeModule } from 'expo';

// import { ExpoScreenRecorderModuleEvents } from './ExpoScreenRecorder.types';

// declare class ExpoScreenRecorderModule extends NativeModule<ExpoScreenRecorderModuleEvents> {
//   hello(): string;
//   setValueAsync(value: string): Promise<void>;
// }

// export default requireNativeModule<ExpoScreenRecorderModule>('ExpoScreenRecorder');


import { NativeModule, requireNativeModule } from 'expo';

export interface RecordingOptions {
  durationLimit?: number;
  quality?: 'low' | 'medium' | 'high';
  includeAudio?: boolean;
}

export interface RecordingResult {
  uri: string;
  duration: number;
}

declare class ExpoScreenRecorderModule extends NativeModule {
  isAvailableAsync(): Promise<boolean>;
  startRecordingAsync(options: RecordingOptions): Promise<void>;
  stopRecordingAsync(): Promise<RecordingResult>;
}

export default requireNativeModule<ExpoScreenRecorderModule>('ExpoScreenRecorder');