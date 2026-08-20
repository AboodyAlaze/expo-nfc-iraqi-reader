# expo-nfc-iraqi-reader

Read Iraqi national ID cards over NFC from Expo / React Native apps.

Implements the ICAO 9303 eMRTD flow from scratch — BAC key derivation, mutual
authentication, and Secure Messaging (3DES-CBC + ISO 9797-1 Alg 3 Retail MAC) —
with no external crypto dependencies.

- **Android** — `NfcAdapter` reader mode
- **iOS** — `CoreNFC` `NFCTagReaderSession` (iPhone 7+, iOS 13+)

---

## ⚠️ Read this first

**This library reads the chip. It does not verify that the chip is genuine.**

A full eMRTD implementation also reads `EF.SOD`, validates its digital signature
against a country signing certificate (CSCA), and compares the hash of every data
group against the values inside the SOD. This library does none of that.

That means a cloned or forged chip will be read and returned as if it were valid.
**Do not use this for identity verification or KYC without adding passive
authentication yourself.** It is suitable for reading a card you hold, with the
cardholder's consent.

**You also cannot read a card without the holder's cooperation.** BAC keys are
derived from data printed on the card, so physical possession is required. This
is by design.

---

## Installation

```bash
npx expo install expo-nfc-iraqi-reader
```

Add the config plugin to `app.json`:

```json
{
  "expo": {
    "plugins": ["expo-nfc-iraqi-reader"]
  }
}
```

Then rebuild the native projects:

```bash
npx expo prebuild --clean
```

The plugin adds the NFC permission on Android, and the reader-session
entitlement, usage description, and eMRTD application identifier on iOS.

**This does not work in Expo Go.** You need a development build.

### Plugin options

```json
{
  "plugins": [
    ["expo-nfc-iraqi-reader", {
      "nfcPermission": "Used to read your national ID card.",
      "selectIdentifiers": ["A0000002472001"]
    }]
  ]
}
```

| Option | Type | Description |
|---|---|---|
| `nfcPermission` | `string` | iOS permission prompt text |
| `selectIdentifiers` | `string[]` | Extra AIDs beyond `A0000002471001` |

### iOS requirements

A **paid Apple Developer account** is required. NFC tag reading is not available
on free personal teams — the capability cannot be enabled and
`isAvailable()` will return `false`.

---

## Usage

```ts
import NfcReader from 'expo-nfc-iraqi-reader';

if (!NfcReader.isAvailable()) {
  // NFC off, unsupported device, or missing entitlement
}

const sub = NfcReader.addProgressListener(({ stage, message }) => {
  console.log(stage, message);
});

try {
  const data = await NfcReader.scan({
    documentNumber: 'B12345678',
    dateOfBirth: '900115',
    dateOfExpiry: '300720',
  });

  console.log(data.fullNameArabic);
} catch (e) {
  console.error(e);
} finally {
  sub.remove();
}
```

---

## Where the three inputs come from

They are read from the MRZ — the three rows of machine-readable text on the back
of the card. Do not use the number printed on the front.

```
I<IRQB12345678412345678901234<<<
     └───────┘▲
     document  check digit — do not include
     number

9001152M3007204IRQ<<<<<<<<<<<8
└────┘  └────┘
birth   expiry
```

| Field | Format | Example |
|---|---|---|
| `documentNumber` | 9 chars, MRZ line 1 | `B12345678` |
| `dateOfBirth` | `YYMMDD` | `900115` |
| `dateOfExpiry` | `YYMMDD` | `300720` |

If any of the three is wrong, the card returns `6300` and `scan()` rejects with
`SCAN_FAILED`. There is no way to brute-force this in practice.

---

## API

### `isAvailable(): boolean`

Whether the device supports NFC reading and it is currently enabled.

### `scan(options): Promise<IdData>`

Starts a session and resolves with the card data. On iOS the system NFC sheet
appears automatically.

### `cancel(): void`

Cancels the running session.

### `addProgressListener(cb): Subscription`

Fires as the read progresses. Call `.remove()` when done.

| `stage` | Meaning |
|---|---|
| `connecting` | Waiting for the card |
| `authenticating` | Performing BAC |
| `reading` | Reading data groups |
| `photo` | Reading DG2 (largest file) |

### `IdData`

| Field | Source | Notes |
|---|---|---|
| `documentNumber` | DG1 | |
| `nationalNumber` | DG1 | Optional data field |
| `surname`, `givenNames` | DG1 | Latin, uppercase |
| `nationality`, `sex` | DG1 | |
| `dateOfBirth`, `dateOfExpiry` | DG1 | `YYMMDD` |
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
| `dateOfIssue` | DG12 | |
| `availableDataGroups` | EF.COM | e.g. `[1, 2, 3, 11, 12, 13, 14]` |

Every field is a string and may be empty — DG11 and DG12 are optional and not
present on every card.

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

Check `faceFormat` first. Some cards store JPEG2000, which neither Android nor
iOS decodes natively — you would need a separate decoder.

---

## Errors

| Code | Cause |
|---|---|
| `NFC_UNAVAILABLE` | NFC disabled, unsupported, or entitlement missing |
| `BAD_TAG` | Card is not ISO-DEP / ISO7816 |
| `SCAN_FAILED` | BAC failed, connection lost, or a read error |
| `CANCELLED` | `cancel()` was called |

`SCAN_FAILED` most often means the MRZ inputs are wrong, or the card moved
mid-read. DG2 takes hundreds of encrypted round trips — rest the card on a table
and put the phone on top rather than holding both.

**Antenna position differs:** centre-back on most Android phones, top edge near
the cameras on iPhone.

---

## Notes on the Iraqi card

Verified against cards issued from 2016 onward:

- Standard ICAO eMRTD applet, AID `A0000002471001`
- BAC is supported; `EF.CardAccess` returns `6982`
- MRZ is TD1 format (three rows of 30)
- DG3 (fingerprints) is present but EAC-protected and not readable
- DG11 packs several values into tag `5F0E` rather than separate tags, so name
  splitting is heuristic and may need adjusting for other cards
- Arabic text encoding varies; the decoder tries UTF-8, double-encoded UTF-8,
  windows-1256, and UTF-16BE in order

Behaviour on cards from other issuers is untested.

---

## Roadmap

- **MRZ camera scanning** — on-device OCR so the three inputs are read from the
  card instead of typed. Any OCR must run locally; uploading a photo of an ID
  document to a cloud service is not an acceptable trade for convenience.
- **Passive authentication** — read `EF.SOD`, verify the signature against the
  CSCA certificate, and compare data group hashes.

---

## Legal

Reading, storing, or transmitting another person's identity data without consent
may violate data protection law. For commercial or government use, contact the
issuing authority for authorisation and the official specification.

---

## نبذة بالعربي

مكتبة لقراءة البطاقة الوطنية العراقية عبر NFC من تطبيقات Expo، تدعم أندرويد و iOS.

**تنبيه مهم:** المكتبة تقرأ الشريحة ولا تتحقق من أصالتها. للتحقق الحقيقي لازم
قراءة `EF.SOD` والتحقق من التوقيع الرقمي مقابل شهادة الدولة — وهذا غير مطبّق هنا.
لا تستعملها بأنظمة تحقق الهوية بدون إضافة هذي الخطوة.

**القراءة تحتاج البطاقة بيدك:** مفاتيح الفتح تُشتق من ثلاث قيم مطبوعة على ظهر
البطاقة (رقم الوثيقة، تاريخ الميلاد، تاريخ الانتهاء)، فما تقدر تقرأ بطاقة أحد
بدون موافقته.

**iOS يحتاج حساب Apple Developer مدفوع** — الحساب المجاني ما يدعم صلاحية NFC.

---

## License

MIT
