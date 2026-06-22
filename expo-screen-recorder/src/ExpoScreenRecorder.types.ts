export type ExpoScreenRecorderModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
};

export type ChangeEventPayload = {
  value: string;
};


export interface RecordingOptions {
  durationLimit?: number;
  quality?: 'low' | 'medium' | 'high';
  includeAudio?: boolean;
}

export interface RecordingResult {
  uri: string;
  duration: number;
}