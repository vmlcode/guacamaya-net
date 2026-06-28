/**
 * CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection, no final XOR).
 * Byte-for-byte mirror of net.guacamaya.proto.Crc16.ccitt (Android).
 */
export function crc16Ccitt(data: Uint8Array, offset = 0, length = data.length): number {
  let crc = 0xffff;
  for (let i = offset; i < offset + length; i++) {
    crc ^= (data[i]! & 0xff) << 8;
    crc &= 0xffff;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x8000) !== 0 ? (crc << 1) ^ 0x1021 : crc << 1;
      crc &= 0xffff;
    }
  }
  return crc;
}
