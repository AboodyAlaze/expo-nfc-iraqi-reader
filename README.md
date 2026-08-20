# expo-nfc-iraqi-reader

Read Iraqi national ID cards over NFC in Expo / React Native apps. The user
points the camera at the back of the card, then taps the card to the phone —
no typing.

Implements the ICAO 9303 eMRTD flow from scratch: BAC key derivation, mutual
authentication, and Secure Messaging (3DES-CBC + ISO 9797-1 Alg 3 Retail MAC),
with no external crypto dependencies.

| | Android | iOS |
|---|---|---|
| NFC | `NfcAdapter` reader mode | `CoreNFC` (iPhone 7+, iOS 13+) |
| MRZ OCR | ML Kit Text Recognition | Vision framework |
| Added app size | ~4 MB | 0 — Vision ships with iOS |

---

## ⚠️ Read this first

**This library reads the chip. It does not verify that the chip is genuine.**

A complete eMRTD implementation also reads `EF.SOD`, validates its signature
against a country signing certificate (CSCA), and checks the hash of every data
group against the SOD. This library does none of that, so a cloned or forged chip
is returned as if it were valid.

**Do not use this for identity verification or KYC without adding passive
authentication yourself.**

Two things follow from how BAC works, and both are by design:

- You cannot read a card you do not physically hold — the keys come from text
  printed on the card.
- All OCR runs on-device. No image ever leaves the phone.

---

## Install

```bash
npx expo install expo-nfc-iraqi-reader
```

`app.json`:

```json
{
  "expo": {
    "plugins": ["expo-nfc-iraqi-reader"]
  }
}
```

```bash
npx expo prebuild --clean
```

The plugin writes the NFC and camera permissions on Android, and the
reader-session entitlement, usage strings, and eMRTD application identifier on
iOS. You do not edit any native file yourself.

**Expo Go will not work** — NFC needs a development build.

**iOS needs a paid Apple Developer account.** NFC tag reading cannot be enabled
on a free personal team; `isAvailable()` returns `false` there.

---

## Quick start

The whole flow in one call:

```tsx
import { useState } from 'react';
import { Button, Text, View } from 'react-native';
import NfcReader, { IdData } from 'expo-nfc-iraqi-reader';

export default function App() {
  const [data, setData] = useState<IdData | null>(null);
  const [status, setStatus] = useState('');

  const handleScan = async () => {
    try {
      const result = await NfcReader.scanFull();
      setData(result);
    } catch (e: any) {
      setStatus(e.message);
    }
  };

  return (
    <View style={{ padding: 20, paddingTop: 80 }}>
      <Button title="Scan ID card" onPress={handleScan} />
      <Text>{status}</Text>
      {data && <Text>{data.fullNameArabic}</Text>}
    </View>
  );
}
```

`scanFull()` opens the camera, reads the MRZ, closes the camera, and starts the
NFC session. Permissions are requested automatically on first use.

---

## Full example

A complete screen with progress messages, the photo, and every field:

```tsx
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Button,
  Image,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import NfcReader, { IdData } from 'expo-nfc-iraqi-reader';

export default function IdScannerScreen() {
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('Tap the button to begin');
  const [data, setData] = useState<IdData | null>(null);

  // Progress messages fire as the read moves through its stages
  useEffect(() => {
    const sub = NfcReader.addProgressListener(({ message }) => {
      setStatus(message);
    });
    return () => sub.remove();
  }, []);

  const handleScan = async () => {
    setBusy(true);
    setData(null);
    setStatus('Point the camera at the back of the card');

    try {
      const result = await NfcReader.scanFull();
      setData(result);
      setStatus('Done');
    } catch (e: any) {
      switch (e.code) {
        case 'CAMERA_DENIED':
          setStatus('Camera access is required to scan the card');
          break;
        case 'MRZ_CANCELLED':
          setStatus('Scan cancelled');
          break;
        case 'NFC_UNAVAILABLE':
          setStatus('Turn on NFC and try again');
          break;
        case 'SCAN_FAILED':
          setStatus('Could not read the chip — hold the card still and retry');
          break;
        default:
          setStatus(e.message ?? 'Something went wrong');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Button title="Scan ID card" onPress={handleScan} disabled={busy} />

      {busy && <ActivityIndicator style={{ marginVertical: 16 }} />}
      <Text style={styles.status}>{status}</Text>

      {data && (
        <View style={styles.results}>
          {data.faceImageBase64 && (
            <Image
              source={{ uri: `data:image/jpeg;base64,${data.faceImageBase64}` }}
              style={styles.photo}
            />
          )}

          <Field label="Full name" value={data.fullNameArabic} />
          <Field label="Surname" value={data.surnameArabic} />
          <Field label="Mother's name" value={data.motherName} />
          <Field label="Grandfather's name" value={data.grandfatherName} />
          <Field label="Place of birth" value={data.placeOfBirth} />
          <Field label="Date of birth" value={data.dateOfBirth} />
          <Field label="Sex" value={data.sex} />
          <Field label="Nationality" value={data.nationality} />

          <Field label="National number" value={data.nationalNumber} />
          <Field label="Document number" value={data.documentNumber} />
          <Field label="Issuing authority" value={data.issuingAuthority} />
          <Field label="Date of issue" value={data.dateOfIssue} />
          <Field label="Date of expiry" value={data.dateOfExpiry} />
        </View>
      )}
    </ScrollView>
  );
}

function Field({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}: </Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { padding: 20, paddingTop: 80 },
  status: { marginTop: 12, color: '#555' },
  results: { marginTop: 24 },
  photo: {
    width: 150,
    height: 190,
    borderRadius: 12,
    alignSelf: 'center',
    marginBottom: 20,
  },
  row: { flexDirection: 'row', marginVertical: 3 },
  rowLabel: { fontWeight: '600' },
  rowValue: { flexShrink: 1 },
});
```

---

## Confirming the MRZ before reading the chip

Split the flow when you want the user to check the scanned values first. Useful
if you expect worn or damaged cards.

```tsx
import { useState } from 'react';
import { Button, Text, TextInput, View } from 'react-native';
import NfcReader, { IdData, MrzKeys } from 'expo-nfc-iraqi-reader';

export default function TwoStepScanner() {
  const [keys, setKeys] = useState<MrzKeys | null>(null);
  const [data, setData] = useState<IdData | null>(null);

  const scanMrz = async () => {
    const result = await NfcReader.scanMrz();
    setKeys(result);
  };

  const readChip = async () => {
    if (!keys) return;
    const result = await NfcReader.scan(keys);
    setData(result);
  };

  return (
    <View style={{ padding: 20, paddingTop: 80 }}>
      <Button title="1. Scan the MRZ" onPress={scanMrz} />

      {keys && (
        <View style={{ marginTop: 20 }}>
          <Text>Check these values:</Text>
          <TextInput
            value={keys.documentNumber}
            onChangeText={(t) => setKeys({ ...keys, documentNumber: t })}
          />
          <TextInput
            value={keys.dateOfBirth}
            onChangeText={(t) => setKeys({ ...keys, dateOfBirth: t })}
          />
          <TextInput
            value={keys.dateOfExpiry}
            onChangeText={(t) => setKeys({ ...keys, dateOfExpiry: t })}
          />
          <Button title="2. Read the chip" onPress={readChip} />
        </View>
      )}

      {data && <Text>{data.fullNameArabic}</Text>}
    </View>
  );
}
```

Values from `scanMrz()` have already passed their MRZ check digits, so they are
almost always correct — this step is a safety net, not a necessity.

---

## Manual entry

If you skip the camera entirely, collect the three values yourself and call
`scan()` directly:

```tsx
const data = await NfcReader.scan({
  documentNumber: 'B12345678',  // 9 characters
  dateOfBirth: '900115',        // YYMMDD
  dateOfExpiry: '300720',       // YYMMDD
});
```

They come from the MRZ — the rows of machine-readable text on the **back** of the
card. The number printed on the front will not work.

```
I<IRQB12345678412345678901234<<<
     └───────┘▲
     document  check digit — do not include
     number

9001152M3007204IRQ<<<<<<<<<<<8
└────┘  └────┘
birth   expiry
```

If any of the three is wrong the chip returns `6300` and `scan()` rejects with
`SCAN_FAILED`.

---

## Checking availability before showing the button

```tsx
import { useEffect, useState } from 'react';
import NfcReader from 'expo-nfc-iraqi-reader';

function useNfcReady() {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setReady(NfcReader.isAvailable());
  }, []);

  return ready;
}
```

On Android `isAvailable()` returns `false` when the hardware is missing **or**
NFC is switched off — send the user to settings if you want to be helpful. On
iOS it returns `false` when the entitlement is missing, which in practice means
the app was built with a free Apple Developer team.

---

## Requesting the camera up front

`scanMrz()` and `scanFull()` both prompt on first use, so this is optional. Use
it if you want the permission dialog to appear somewhere earlier in your flow:

```tsx
const granted = await NfcReader.requestCameraPermission();
if (!granted) {
  // Point the user at their system settings
}
```

`hasCameraPermission()` tells you the current state without prompting.

---

## API

| Function | Returns | Notes |
|---|---|---|
| `isAvailable()` | `boolean` | NFC supported and enabled |
| `hasCameraPermission()` | `boolean` | Does not prompt |
| `requestCameraPermission()` | `Promise<boolean>` | Prompts |
| `scanMrz()` | `Promise<MrzKeys>` | Opens the camera |
| `scan(keys)` | `Promise<IdData>` | Reads the chip |
| `scanFull()` | `Promise<IdData>` | Camera, then chip |
| `cancel()` | `void` | Cancels the NFC session |
| `addProgressListener(cb)` | `Subscription` | Call `.remove()` when done |

### Progress stages

| `stage` | Meaning |
|---|---|
| `connecting` | Waiting for the card |
| `authenticating` | Performing BAC |
| `reading` | Reading data groups |
| `photo` | Reading DG2, the largest file |

### `MrzKeys`

```ts
{
  documentNumber: string;  // 9 characters
  dateOfBirth: string;     // YYMMDD
  dateOfExpiry: string;    // YYMMDD
}
```

### `IdData`

| Field | Source | Notes |
|---|---|---|
| `documentNumber` | DG1 | |
| `nationalNumber` | DG1 | Optional data field |
| `surname`, `givenNames` | DG1 | Latin, uppercase |
| `nationality`, `sex` | DG1 | |
| `dateOfBirth`, `dateOfExpiry` | DG1 | `DD/MM/YYYY` |
| `dateOfBirthRaw`, `dateOfExpiryRaw` | DG1 | `YYMMDD` |
| `rawMrz` | DG1 | Full MRZ string |
| `faceImageBase64` | DG2 | Base64, no `data:` prefix |
| `faceFormat` | DG2 | `JPEG` or `JPEG2000` |
| `fullNameArabic` | DG11 | |
| `surnameArabic` | DG11 | |
| `motherName` | DG11 | |
| `grandfatherName` | DG11 | |
| `placeOfBirth` | DG11 | |
| `personalNumber` | DG11 | |
| `issuingAuthority` | DG12 | |
| `dateOfIssue` | DG12 | `DD/MM/YYYY` |
| `dateOfIssueRaw` | DG12 | Raw form |
| `availableDataGroups` | EF.COM | e.g. `[1, 2, 3, 11, 12, 13, 14]` |

Every field is a string and may be empty — DG11 and DG12 are optional and not
present on every card. Guard before displaying.

---

## Displaying the photo

```tsx
{data.faceImageBase64 && (
  <Image
    source={{ uri: `data:image/jpeg;base64,${data.faceImageBase64}` }}
    style={{ width: 150, height: 190 }}
  />
)}
```

Check `faceFormat` first. A few cards store JPEG2000, which neither platform
decodes natively:

```tsx
{data.faceFormat === 'JPEG2000' && (
  <Text>Photo needs a JPEG2000 decoder</Text>
)}
```

---

## Plugin options

```json
{
  "plugins": [
    ["expo-nfc-iraqi-reader", {
      "nfcPermission": "Used to read your ID card chip.",
      "cameraPermission": "Used to scan the code on the back of your card.",
      "selectIdentifiers": ["A0000002472001"]
    }]
  ]
}
```

| Option | Type | Description |
|---|---|---|
| `nfcPermission` | `string` | iOS NFC prompt text |
| `cameraPermission` | `string` | iOS camera prompt text |
| `selectIdentifiers` | `string[]` | Extra AIDs beyond `A0000002471001` |

---

## Errors

Every rejection carries a `code`:

| Code | Cause | Suggested response |
|---|---|---|
| `NFC_UNAVAILABLE` | NFC off, unsupported, or entitlement missing | Ask the user to enable NFC |
| `CAMERA_DENIED` | Camera permission refused | Point at system settings |
| `MRZ_CANCELLED` | User closed the scanner | Silent |
| `BAD_TAG` | Not an ISO-DEP / ISO7816 card | Wrong card |
| `SCAN_FAILED` | BAC failed, card moved, or read error | Offer a retry |
| `CANCELLED` | `cancel()` was called | Silent |

`SCAN_FAILED` almost always means the card moved. DG2 takes hundreds of
encrypted round trips, so a moment of lost contact aborts the whole read. Rest
the card on a table and put the phone on top rather than holding both.

**Antenna position differs:** centre of the back on most Android phones, top edge
near the cameras on iPhone.

---

## Notes on the Iraqi card

Verified against cards issued from 2016 onward:

- Standard ICAO eMRTD applet, AID `A0000002471001`
- BAC is supported; `EF.CardAccess` returns `6982`
- MRZ is TD1 format — three rows of 30 characters
- DG3 (fingerprints) is present but EAC-protected and not readable
- DG11 packs several values into tag `5F0E` rather than separate tags, so name
  splitting is heuristic and may need adjusting for other issuers
- Arabic text encoding varies between fields; the decoder tries UTF-8,
  double-encoded UTF-8, windows-1256, and UTF-16BE in that order

Behaviour on cards from other countries is untested.

---

## Roadmap

- **Passive authentication** — read `EF.SOD`, verify its signature against the
  CSCA certificate, and compare data group hashes.

---

## Legal

Reading, storing, or transmitting another person's identity data without consent
may violate data protection law. For commercial or government use, contact the
issuing authority for authorisation and the official specification.

---

## نبذة بالعربي

مكتبة لقراءة البطاقة الوطنية العراقية عبر NFC من تطبيقات Expo، تدعم أندرويد و iOS،
مع مسح الـ MRZ بالكاميرا — فما يحتاج المستخدم يكتب أي شي.

```ts
const data = await NfcReader.scanFull();
console.log(data.fullNameArabic);
```

**تنبيه مهم:** المكتبة تقرأ الشريحة ولا تتحقق من أصالتها. للتحقق الحقيقي لازم
قراءة `EF.SOD` والتحقق من التوقيع الرقمي مقابل شهادة الدولة، وهذا غير مطبّق هنا.
لا تستعملها بأنظمة تحقق الهوية بدون إضافة هذي الخطوة.

**القراءة تحتاج البطاقة بيدك:** مفاتيح الفتح تُشتق من قيم مطبوعة على ظهر البطاقة،
فما تقدر تقرأ بطاقة أحد بدون موافقته. وكل معالجة الصور تصير على الجهاز نفسه.

**iOS يحتاج حساب Apple Developer مدفوع** — الحساب المجاني ما يدعم صلاحية NFC.

---

## License

MIT
