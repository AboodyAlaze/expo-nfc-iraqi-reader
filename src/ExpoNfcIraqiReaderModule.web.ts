import { registerWebModule, NativeModule } from 'expo';

// ExpoNfcIraqiReaderModule is not available on the web platform.
class ExpoNfcIraqiReaderModule extends NativeModule<{}> {}

export default registerWebModule(ExpoNfcIraqiReaderModule, 'ExpoNfcIraqiReaderModule');
