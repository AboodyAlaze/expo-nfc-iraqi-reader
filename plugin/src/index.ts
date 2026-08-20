import {
  ConfigPlugin,
  withAndroidManifest,
  withEntitlementsPlist,
  withInfoPlist,
  AndroidConfig,
  createRunOncePlugin,
} from 'expo/config-plugins';

const EMRTD_AID = 'A0000002471001';

type Options = {
  /** نص إذن NFC اللي يظهر للمستخدم على iOS */
  nfcPermission?: string;
  /** نص إذن الكاميرا اللي يظهر للمستخدم على iOS */
  cameraPermission?: string;
  /** معرّفات تطبيقات إضافية مسموح الاتصال بيها */
  selectIdentifiers?: string[];
};

/** أندرويد: صلاحيات NFC والكاميرا */
const withNfcAndroid: ConfigPlugin = (config) =>
  withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults;

    AndroidConfig.Permissions.ensurePermission(manifest, 'android.permission.NFC');
    AndroidConfig.Permissions.ensurePermission(manifest, 'android.permission.CAMERA');

    manifest.manifest['uses-feature'] = manifest.manifest['uses-feature'] ?? [];
    const features = manifest.manifest['uses-feature'] as any[];

    const addFeature = (name: string) => {
      if (!features.some((f) => f?.$?.['android:name'] === name)) {
        features.push({
          $: {
            'android:name': name,
            'android:required': 'false',
          },
        });
      }
    };

    addFeature('android.hardware.nfc');
    addFeature('android.hardware.camera');

    return cfg;
  });

/** iOS: entitlement لقراءة الوسوم */
const withNfcEntitlements: ConfigPlugin = (config) =>
  withEntitlementsPlist(config, (cfg) => {
    const key = 'com.apple.developer.nfc.readersession.formats';
    const current = (cfg.modResults[key] as string[]) ?? [];
    if (!current.includes('TAG')) current.push('TAG');
    cfg.modResults[key] = current;
    return cfg;
  });

/** iOS: الأوصاف والـ AID المسموح */
const withNfcInfoPlist: ConfigPlugin<Options> = (config, opts = {}) =>
  withInfoPlist(config, (cfg) => {
    cfg.modResults.NFCReaderUsageDescription =
      opts.nfcPermission ??
      cfg.modResults.NFCReaderUsageDescription ??
      'This app reads your national ID card over NFC.';

    cfg.modResults.NSCameraUsageDescription =
      opts.cameraPermission ??
      cfg.modResults.NSCameraUsageDescription ??
      'Used to scan the MRZ on the back of your ID card.';

    const key = 'com.apple.developer.nfc.readersession.iso7816.select-identifiers';
    const current = (cfg.modResults[key] as string[]) ?? [];
    const wanted = [EMRTD_AID, ...(opts.selectIdentifiers ?? [])];

    for (const aid of wanted) {
      if (!current.includes(aid)) current.push(aid);
    }
    cfg.modResults[key] = current;

    return cfg;
  });

const withNfcIraqiReader: ConfigPlugin<Options> = (config, opts = {}) => {
  config = withNfcAndroid(config);
  config = withNfcEntitlements(config);
  config = withNfcInfoPlist(config, opts);
  return config;
};

export default createRunOncePlugin(withNfcIraqiReader, 'expo-nfc-iraqi-reader', '0.1.0');
