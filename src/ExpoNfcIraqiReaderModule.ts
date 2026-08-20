import { NativeModule, requireNativeModule } from 'expo';

declare class ExpoNfcIraqiReaderModule extends NativeModule<{}> {}

export default requireNativeModule<ExpoNfcIraqiReaderModule>('ExpoNfcIraqiReader');
