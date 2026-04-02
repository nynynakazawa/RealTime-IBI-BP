#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Optional, Sequence
import time

import usb.core
import usb.util

try:
    import libusb_package
except ImportError:  # pragma: no cover - optional dependency at runtime
    libusb_package = None


class TUSBAdapioError(RuntimeError):
    """Base error for TUSB-ADAPIO access."""


class TUSBAdapioNotFoundError(TUSBAdapioError):
    """Raised when no matching USB device is present."""


class TUSBAdapioProtocolError(TUSBAdapioError):
    """Raised when the device responds with an unexpected payload."""


@dataclass(frozen=True)
class DeviceSummary:
    vendor_id: int
    product_id: int
    manufacturer: Optional[str]
    product: Optional[str]
    serial_number: Optional[str]
    identity: Optional[int]
    bus: Optional[int]
    address: Optional[int]


def get_default_backend():
    if libusb_package is None:
        return None
    return libusb_package.get_libusb1_backend()


class TUSBAdapio:
    """Minimal macOS/Linux driver for Turtle Industry TUSB-ADAPIO."""

    VID = 0x0BA0
    PID = 0x0001

    INTERFACE_NUMBER = 0
    ALT_SETTING = 0
    EP_OUT = 0x02
    EP_IN = 0x81

    ADC_CHANNEL_COUNT = 6
    ADC_TRIGGER_CHANNEL_COUNT = 4
    ADC_MAX_VALUE = 1023
    ADC_BUFFER_MAX = 512

    def __init__(
        self,
        *,
        vendor_id: int = VID,
        product_id: int = PID,
        read_timeout_ms: int = 1000,
        write_timeout_ms: int = 1000,
        backend=None,
    ) -> None:
        self.vendor_id = vendor_id
        self.product_id = product_id
        self.read_timeout_ms = read_timeout_ms
        self.write_timeout_ms = write_timeout_ms
        self.backend = backend if backend is not None else get_default_backend()
        self._device = None
        self._claimed = False
        self._detached_kernel_driver = False
        self.identity: Optional[int] = None

    def open(self) -> "TUSBAdapio":
        if self._device is not None:
            return self

        device = usb.core.find(
            idVendor=self.vendor_id,
            idProduct=self.product_id,
            backend=self.backend,
        )
        if device is None:
            raise TUSBAdapioNotFoundError(
                f"TUSB-ADAPIO not found (vid=0x{self.vendor_id:04x}, pid=0x{self.product_id:04x})"
            )

        try:
            if device.is_kernel_driver_active(self.INTERFACE_NUMBER):
                device.detach_kernel_driver(self.INTERFACE_NUMBER)
                self._detached_kernel_driver = True
        except (NotImplementedError, usb.core.USBError):
            pass

        try:
            device.set_configuration()
        except usb.core.USBError as exc:
            message = str(exc).lower()
            if "busy" not in message and "resource" not in message:
                raise

        usb.util.claim_interface(device, self.INTERFACE_NUMBER)
        self._claimed = True
        self._device = device
        self.identity = self.get_identity()
        return self

    def close(self) -> None:
        if self._device is None:
            return

        try:
            if self._claimed:
                usb.util.release_interface(self._device, self.INTERFACE_NUMBER)
        except usb.core.USBError:
            pass
        finally:
            self._claimed = False

        try:
            if self._detached_kernel_driver:
                self._device.attach_kernel_driver(self.INTERFACE_NUMBER)
        except (NotImplementedError, usb.core.USBError):
            pass
        finally:
            self._detached_kernel_driver = False

        usb.util.dispose_resources(self._device)
        self._device = None

    def __enter__(self) -> "TUSBAdapio":
        return self.open()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def summary(self) -> DeviceSummary:
        self._require_open()
        return DeviceSummary(
            vendor_id=self.vendor_id,
            product_id=self.product_id,
            manufacturer=getattr(self._device, "manufacturer", None),
            product=getattr(self._device, "product", None),
            serial_number=getattr(self._device, "serial_number", None),
            identity=self.identity,
            bus=getattr(self._device, "bus", None),
            address=getattr(self._device, "address", None),
        )

    def get_identity(self) -> int:
        response = self._command_response((0x40, 0x00), 2)
        return response[1]

    def adc_single_sample(self, channel: int) -> int:
        self._validate_range(channel, 0, self.ADC_CHANNEL_COUNT - 1, "channel")
        response = self._command_response((0x30, channel & 0xFF, 0x00), 3)
        return response[1] | (response[2] << 8)

    def read_scan(self, channels: Iterable[int]) -> list[int]:
        return [self.adc_single_sample(channel) for channel in channels]

    def adc_digital_trigger(self, end_channel: int, buffer_size: int) -> None:
        self._validate_range(
            end_channel,
            0,
            self.ADC_TRIGGER_CHANNEL_COUNT - 1,
            "end_channel",
        )
        self._validate_range(buffer_size, 1, self.ADC_BUFFER_MAX, "buffer_size")
        payload = (
            0x31,
            end_channel & 0xFF,
            buffer_size & 0xFF,
            (buffer_size >> 8) & 0xFF,
        )
        self._write(payload)

    def adc_analog_trigger(
        self,
        end_channel: int,
        buffer_size: int,
        threshold: int,
        trigger_channel: int,
        falling_edge: bool = False,
    ) -> None:
        self._validate_range(
            end_channel,
            0,
            self.ADC_TRIGGER_CHANNEL_COUNT - 1,
            "end_channel",
        )
        self._validate_range(buffer_size, 1, self.ADC_BUFFER_MAX, "buffer_size")
        self._validate_range(threshold, 0, self.ADC_MAX_VALUE, "threshold")
        self._validate_range(trigger_channel, 0, end_channel, "trigger_channel")
        command = 0x33 if falling_edge else 0x32
        payload = (
            command,
            end_channel & 0xFF,
            buffer_size & 0xFF,
            (buffer_size >> 8) & 0xFF,
            (threshold & 0x03) << 6,
            (threshold >> 2) & 0xFF,
            trigger_channel & 0xFF,
            0x00,
        )
        self._write(payload)

    def adc_get_status(self) -> tuple[int, int]:
        response = self._command_response((0x34, 0x00), 4)
        running = response[1]
        sampled_num = response[2] | (response[3] << 8)
        return running, sampled_num

    def adc_get_data(self, length: int, *, settle_ms: int = 100) -> list[int]:
        self._validate_range(length, 0, self.ADC_BUFFER_MAX, "length")
        if length == 0:
            return []

        self._write((0x35, length & 0xFF, (length >> 8) & 0xFF, 0x00))
        if settle_ms > 0:
            time.sleep(settle_ms / 1000.0)
        response = self._read(length * 2)
        values: list[int] = []
        for index in range(0, len(response), 2):
            values.append((response[index] << 2) | (response[index + 1] >> 6))
        return values

    def _require_open(self) -> None:
        if self._device is None:
            raise TUSBAdapioError("device is not open")

    def _write(self, payload: Sequence[int]) -> None:
        self._require_open()
        request = bytes(payload)
        written = self._device.write(
            self.EP_OUT,
            request,
            timeout=self.write_timeout_ms,
        )
        if written != len(request):
            raise TUSBAdapioProtocolError(
                f"short write: expected {len(request)} bytes, wrote {written}"
            )

    def _read(self, expected_length: int) -> bytes:
        self._require_open()
        data = self._device.read(
            self.EP_IN,
            expected_length,
            timeout=self.read_timeout_ms,
        )
        response = bytes(data)
        if len(response) != expected_length:
            raise TUSBAdapioProtocolError(
                f"short read: expected {expected_length} bytes, got {len(response)}"
            )
        return response

    def _command_response(self, payload: Sequence[int], response_len: int) -> bytes:
        self._write(payload)
        response = self._read(response_len)
        command = payload[0]
        if not response or response[0] != command:
            raise TUSBAdapioProtocolError(
                "unexpected command echo: "
                f"sent 0x{command:02x}, received {response.hex(' ')}"
            )
        return response

    @staticmethod
    def _validate_range(value: int, lower: int, upper: int, name: str) -> None:
        if value < lower or value > upper:
            raise ValueError(f"{name} must be in [{lower}, {upper}], got {value}")


def counts_to_volts(raw_count: int, *, full_scale_volts: float = 5.0) -> float:
    return raw_count * full_scale_volts / TUSBAdapio.ADC_MAX_VALUE
