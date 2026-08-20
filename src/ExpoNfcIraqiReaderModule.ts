import { NativeModule, requireNativeModule } from 'expo';

import type {
  ExpoNfcIraqiReaderModuleEvents,
  IdData,
  MrzKeys,
  ScanOptions,
} from './ExpoNfcIraqiReader.types';

declare class ExpoNfcIraqiReaderModule extends NativeModule<ExpoNfcIraqiReaderModuleEvents> {
  isAvailable(): boolean;
  hasCameraPermission(): boolean;
  scan(options: ScanOptions): Promise<IdData>;
  scanMrz(): Promise<MrzKeys>;
  cancel(): void;
}

export default requireNativeModule<ExpoNfcIraqiReaderModule>('ExpoNfcIraqiReader');
