import { NativeModule, requireNativeModule } from 'expo';

import type {
  ExpoNfcIraqiReaderModuleEvents,
  IdData,
  ScanOptions,
} from './ExpoNfcIraqiReader.types';

declare class ExpoNfcIraqiReaderModule extends NativeModule<ExpoNfcIraqiReaderModuleEvents> {
  isAvailable(): boolean;
  scan(options: ScanOptions): Promise<IdData>;
  cancel(): void;
}

export default requireNativeModule<ExpoNfcIraqiReaderModule>('ExpoNfcIraqiReader');
