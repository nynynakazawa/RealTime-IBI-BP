# TUSB-ADAPIO Protocol Notes

These notes were derived from:

- the official Turtle Industry Windows package in `vendor/`
- the exported API in `DEV/TOOLS/Tuadapio.h`
- static inspection of `DRIVER/sub/TUADAPIO.dll`
- direct USB tests on macOS

## USB Identity

- Vendor ID: `0x0ba0`
- Product ID: `0x0001`
- Product string: `TUSB-ADAPIO`

Observed endpoints on interface 0:

- `0x02`: bulk OUT
- `0x81`: bulk IN
- `0x06`: bulk OUT
- `0x85`: bulk IN

The Windows DLL uses `0x02` and `0x81`.

## Confirmed Commands

The first byte is the command code.

| Command | Meaning | Direction |
| --- | --- | --- |
| `0x40` | device identity query | write, then read 2 bytes |
| `0x30` | ADC single sample | write 3 bytes, then read 3 bytes |
| `0x31` | ADC digital trigger start | write 4 bytes |
| `0x32` | ADC analog trigger start, rising | write 8 bytes |
| `0x33` | ADC analog trigger start, falling | write 8 bytes |
| `0x34` | ADC status | write 2 bytes, then read 4 bytes |
| `0x35` | ADC buffered data fetch | write 4 bytes, wait, then read `2 * N` bytes |

Other command codes were visible in the DLL, but are not used yet here:

- `0x10` to `0x19`: PIO and clock control
- `0x20`, `0x21`: DAC output

## Response Formats

### Identity

Request:

```text
40 00
```

Observed response:

```text
40 00
```

The second byte is the device identity reported by the Windows API.

### ADC Single Sample

Request:

```text
30 <channel> 00
```

Response:

```text
30 <low> <high>
```

Decoded value:

```text
raw = low + 256 * high
```

### ADC Status

Request:

```text
34 00
```

Response:

```text
34 <running> <count_low> <count_high>
```

Decoded fields:

```text
running = response[1]
sampled_num = response[2] + 256 * response[3]
```

### ADC Buffered Data

Request:

```text
35 <count_low> <count_high> 00
```

Then wait about `100 ms`, then read `2 * count` bytes.

Each 10-bit sample is packed as:

```text
sample = (byte0 << 2) | (byte1 >> 6)
```

This matches the logic in `Adapio_Adc_GetDatas`.

## Trigger Parameter Packing

### Digital Trigger

Packet:

```text
31 <end_channel> <buffer_low> <buffer_high>
```

Validated by the DLL:

- `end_channel`: `0..3`
- `buffer_size`: `1..512`

### Analog Trigger

Packet:

```text
32/33 <end_channel> <buffer_low> <buffer_high> <threshold_low2_shifted> <threshold_high8> <trigger_channel> 00
```

Where:

```text
threshold_low2_shifted = (threshold & 0x03) << 6
threshold_high8 = threshold >> 2
```

Validated by the DLL:

- `end_channel`: `0..3`
- `buffer_size`: `1..512`
- `threshold`: `0..1023`
- `trigger_channel`: `0..end_channel`

## Practical Capture Notes

- For the CNAP AUX use case, plain polling with `0x30` is currently the most straightforward path.
- CNAP AUX itself is documented elsewhere in this repo as a 100 Hz analog output path.
- Calibration or finger-change intervals should be tagged and excluded later because CNAP can emit square calibration signals in those periods.
