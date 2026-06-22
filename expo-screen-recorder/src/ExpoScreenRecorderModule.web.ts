import { registerWebModule, NativeModule } from 'expo';

import { ExpoScreenRecorderModuleEvents } from './ExpoScreenRecorder.types';

class ExpoScreenRecorderModule extends NativeModule<ExpoScreenRecorderModuleEvents> {
  hello() {
    return 'Hello world! 👋';
  }

  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
}

export default registerWebModule(ExpoScreenRecorderModule, 'ExpoScreenRecorderModule');
